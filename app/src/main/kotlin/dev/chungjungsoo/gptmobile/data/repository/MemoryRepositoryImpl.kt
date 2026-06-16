package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.database.dao.ChatClassificationDao
import dev.chungjungsoo.gptmobile.data.database.dao.PersonalMemoryDao
import dev.chungjungsoo.gptmobile.data.database.entity.ChatClassification
import dev.chungjungsoo.gptmobile.data.database.entity.ChatMode
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryScope
import dev.chungjungsoo.gptmobile.data.database.entity.MemorySensitivity
import dev.chungjungsoo.gptmobile.data.database.entity.MemorySource
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryStatus
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryType
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PersonalMemory
import javax.inject.Inject
import kotlin.math.max

class MemoryRepositoryImpl @Inject constructor(
    private val personalMemoryDao: PersonalMemoryDao,
    private val chatClassificationDao: ChatClassificationDao
) : MemoryRepository {

    override suspend fun classifyChat(
        chatId: Int,
        latestUserMessage: MessageV2,
        allUserMessages: List<MessageV2>
    ): ChatClassification {
        val classification = classifyChatByRules(
            chatId = chatId,
            latestUserMessage = latestUserMessage,
            allUserMessages = allUserMessages
        )
        chatClassificationDao.upsert(classification)
        return classification
    }

    override suspend fun retrieveMemories(
        classification: ChatClassification,
        latestUserMessage: MessageV2,
        limit: Int
    ): List<PersonalMemory> {
        val now = nowSeconds()
        val scored = personalMemoryDao
            .getByStatuses(listOf(MemoryStatus.ACTIVE))
            .mapNotNull { memory ->
                val score = scoreMemory(memory, classification, latestUserMessage)
                memory.takeIf { score >= MIN_RETRIEVAL_SCORE }?.let { it to score }
            }
            .sortedWith(compareByDescending<Pair<PersonalMemory, Float>> { it.second }.thenByDescending { it.first.importance })
            .take(limit)
            .map { it.first }

        if (scored.isNotEmpty()) {
            personalMemoryDao.updateLastAccessed(scored.map { it.id }, now)
        }

        return scored
    }

    override fun buildMemoryPrompt(
        memories: List<PersonalMemory>,
        classification: ChatClassification
    ): String? = buildMemoryPromptByRules(memories, classification)

    override suspend fun learnFromChat(
        chatRoom: ChatRoomV2,
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>
    ) {
        val learnedMemories = userMessages
            .takeLast(6)
            .flatMap { learnMemoriesFromMessage(chatRoom, it) }
            .distinctBy { it.type to it.content }

        learnedMemories.forEach { memory ->
            val existing = personalMemoryDao.findByTypeAndContent(memory.type, memory.content)
            if (existing == null) {
                personalMemoryDao.insert(memory)
            } else if (memory.importance > existing.importance || memory.confidence > existing.confidence) {
                personalMemoryDao.update(
                    existing.copy(
                        importance = max(existing.importance, memory.importance),
                        confidence = max(existing.confidence, memory.confidence),
                        source = if (memory.source == MemorySource.EXPLICIT_USER_STATEMENT) memory.source else existing.source,
                        status = MemoryStatus.ACTIVE,
                        updatedAt = nowSeconds()
                    )
                )
            }
        }
    }

    override suspend fun getAllMemories(): List<PersonalMemory> = personalMemoryDao.getAll()

    override suspend fun updateMemoryContent(memoryId: Int, content: String) {
        personalMemoryDao.updateContent(memoryId, content.trim(), nowSeconds())
    }

    override suspend fun markMemoryResolved(memoryId: Int) {
        personalMemoryDao.updateStatus(memoryId, MemoryStatus.RESOLVED, nowSeconds())
    }

    override suspend fun archiveMemory(memoryId: Int) {
        personalMemoryDao.updateStatus(memoryId, MemoryStatus.ARCHIVED, nowSeconds())
    }

    override suspend fun deleteMemory(memoryId: Int) {
        personalMemoryDao.deleteById(memoryId)
    }

    private fun learnMemoriesFromMessage(
        chatRoom: ChatRoomV2,
        message: MessageV2
    ): List<PersonalMemory> = learnMemoriesFromText(
        text = message.content,
        chatId = chatRoom.id,
        createdAt = nowSeconds()
    )

    companion object {
        private const val MIN_RETRIEVAL_SCORE = 2.5f

        internal fun classifyChatByRules(
            chatId: Int,
            latestUserMessage: MessageV2,
            allUserMessages: List<MessageV2>,
            updatedAt: Long = nowSeconds()
        ): ChatClassification {
            val text = latestUserMessage.content
            val allText = (allUserMessages.map { it.content } + text).joinToString("\n")
            val mode = when {
                text.containsAny(EMOTIONAL_KEYWORDS) -> ChatMode.EMOTIONAL_SUPPORT
                text.containsAny(PRODUCTIVITY_KEYWORDS) -> ChatMode.PRODUCTIVITY_LIGHT
                text.containsAny(ADVICE_KEYWORDS) && text.containsAny(PERSONAL_UPDATE_KEYWORDS) -> ChatMode.ADVICE_SEEKING
                text.containsAny(PERSONAL_UPDATE_KEYWORDS) -> ChatMode.PERSONAL_UPDATE
                text.containsAny(CASUAL_KEYWORDS) -> ChatMode.CASUAL_CHAT
                text.containsAny(RELATIONSHIP_KEYWORDS) -> ChatMode.RELATIONSHIP_TALK
                text.containsAny(CREATIVE_KEYWORDS) -> ChatMode.CREATIVE_PLAY
                text.containsAny(LEARNING_KEYWORDS) -> ChatMode.LEARNING_LIGHT
                else -> ChatMode.CASUAL_CHAT
            }

            return ChatClassification(
                chatId = chatId,
                mode = mode,
                domains = extractDomains(allText),
                memoryNeeds = memoryNeedsForMode(mode),
                entities = extractSimpleEntities(allText),
                sensitivity = if (text.containsAny(SENSITIVE_KEYWORDS)) MemorySensitivity.SENSITIVE else MemorySensitivity.NORMAL,
                confidence = 0.78f,
                updatedAt = updatedAt
            )
        }

        internal fun scoreMemory(
            memory: PersonalMemory,
            classification: ChatClassification,
            latestUserMessage: MessageV2
        ): Float {
            if (memory.status != MemoryStatus.ACTIVE) return 0f
            if (memory.expiresAt?.let { it <= nowSeconds() } == true) return 0f
            if (shouldSkipSensitiveMemory(memory, classification, latestUserMessage)) return 0f

            val typeMatch = if (memory.type in classification.memoryNeeds) 1f else 0f
            val modeMatch = if (memory.type in relevantTypesForMode(classification.mode)) 1f else 0f
            val tagMatch = if (hasTagMatch(memory, classification, latestUserMessage)) 1f else 0f
            val sourceBonus = when (memory.source) {
                MemorySource.USER_CONFIRMED -> 1f
                MemorySource.EXPLICIT_USER_STATEMENT -> 0.7f
                else -> 0f
            }
            val sensitivityPenalty = when (memory.sensitivity) {
                MemorySensitivity.PRIVATE -> 0.5f
                MemorySensitivity.SENSITIVE -> 2f
                else -> 0f
            }
            val irrelevantModePenalty = irrelevantModePenalty(memory, classification)
            val boundaryBonus = if (memory.type == MemoryType.BOUNDARY) 1.2f else 0f

            return typeMatch * 3f +
                modeMatch * 2f +
                tagMatch * 2f +
                memory.importance * 2f +
                memory.confidence +
                sourceBonus +
                boundaryBonus -
                sensitivityPenalty -
                irrelevantModePenalty
        }

        internal fun buildMemoryPromptByRules(
            memories: List<PersonalMemory>,
            classification: ChatClassification
        ): String? {
            val filtered = memories
                .filter { it.status == MemoryStatus.ACTIVE }
                .filterNot { it.sensitivity == MemorySensitivity.SENSITIVE && it.source != MemorySource.USER_CONFIRMED }
                .takeIf { it.isNotEmpty() }
                ?: return null

            return buildString {
                appendLine("Relevant user memories:")
                filtered.forEach { memory ->
                    appendLine("- ${memory.content.trim()}")
                }
                append("Use this only when relevant; do not force mentioning it.")
                if (classification.mode == ChatMode.CASUAL_CHAT) {
                    append(" Keep the conversation natural and avoid bringing up heavy context unless the user points to it.")
                }
            }
        }

        internal fun learnMemoriesFromText(
            text: String,
            chatId: Int,
            createdAt: Long = nowSeconds()
        ): List<PersonalMemory> {
            val cleaned = text.trim()
            if (cleaned.isBlank()) return emptyList()
            if (cleaned.containsAny(SENSITIVE_KEYWORDS)) return emptyList()

            val memories = mutableListOf<PersonalMemory>()
            extractRememberContent(cleaned)?.let { content ->
                memories.add(
                    baseMemory(
                        content = content,
                        type = classifyLearnedContentType(content),
                        chatId = chatId,
                        createdAt = createdAt,
                        source = MemorySource.EXPLICIT_USER_STATEMENT,
                        importance = 0.85f,
                        confidence = 0.95f
                    )
                )
            }

            if (cleaned.containsAny(COMMUNICATION_STYLE_LEARN_KEYWORDS)) {
                memories.add(
                    baseMemory(
                        content = normalizePreferenceContent(cleaned),
                        type = if (cleaned.containsAny(BOUNDARY_KEYWORDS)) MemoryType.BOUNDARY else MemoryType.COMMUNICATION_STYLE,
                        chatId = chatId,
                        createdAt = createdAt,
                        source = MemorySource.EXPLICIT_USER_STATEMENT,
                        importance = 0.8f,
                        confidence = 0.9f
                    )
                )
            }

            if (cleaned.containsAny(IMPORTANT_EVENT_LEARN_KEYWORDS)) {
                memories.add(
                    baseMemory(
                        content = normalizeEventContent(cleaned),
                        type = if (cleaned.containsAny(LIFE_CONTEXT_KEYWORDS)) MemoryType.LIFE_CONTEXT else MemoryType.IMPORTANT_EVENT,
                        chatId = chatId,
                        createdAt = createdAt,
                        source = MemorySource.EXPLICIT_USER_STATEMENT,
                        importance = 0.75f,
                        confidence = 0.82f
                    )
                )
            }

            return memories
                .filter { it.content.length >= 4 }
                .distinctBy { it.type to it.content }
        }

        private fun baseMemory(
            content: String,
            type: String,
            chatId: Int,
            createdAt: Long,
            source: String,
            importance: Float,
            confidence: Float
        ): PersonalMemory = PersonalMemory(
            content = content.trim().replace(Regex("\\s+"), " ").take(240),
            type = type,
            scope = if (chatId > 0) MemoryScope.CHAT else MemoryScope.GLOBAL,
            tags = extractTags(content),
            importance = importance,
            confidence = confidence,
            source = source,
            sensitivity = MemorySensitivity.NORMAL,
            status = MemoryStatus.ACTIVE,
            createdAt = createdAt,
            updatedAt = createdAt
        )

        private fun extractRememberContent(text: String): String? {
            val match = Regex("(记住|請記住|请记住|以后记得|以后請記得|以后请记得)[:：,，\\s]*(.+)").find(text)
            return match?.groupValues?.getOrNull(2)?.trim('。', '.', ' ', '，', ',')?.takeIf { it.isNotBlank() }
        }

        private fun classifyLearnedContentType(content: String): String = when {
            content.containsAny(BOUNDARY_KEYWORDS) -> MemoryType.BOUNDARY
            content.containsAny(COMMUNICATION_STYLE_LEARN_KEYWORDS) -> MemoryType.COMMUNICATION_STYLE
            content.containsAny(IMPORTANT_EVENT_LEARN_KEYWORDS) -> MemoryType.IMPORTANT_EVENT
            content.containsAny(PRODUCTIVITY_KEYWORDS) -> MemoryType.LIGHT_PRODUCTIVITY_PREFERENCE
            else -> MemoryType.STABLE_PROFILE
        }

        private fun normalizePreferenceContent(text: String): String = when {
            text.contains("以后用中文") || text.contains("用中文") -> "用户偏好用中文自然交流。"
            text.contains("别太说教") || text.contains("不要太说教") || text.contains("別太說教") -> "用户不喜欢被过度说教。"
            text.contains("别再") || text.contains("不要再") || text.contains("不喜欢") || text.contains("不喜歡") -> text
            text.contains("我喜欢") || text.contains("我喜歡") -> text
            else -> text
        }

        private fun normalizeEventContent(text: String): String = text.trim().take(240)

        private fun shouldSkipSensitiveMemory(
            memory: PersonalMemory,
            classification: ChatClassification,
            latestUserMessage: MessageV2
        ): Boolean {
            if (memory.sensitivity != MemorySensitivity.SENSITIVE) return false
            if (memory.source != MemorySource.USER_CONFIRMED) return true
            if (classification.mode == ChatMode.CASUAL_CHAT || classification.mode == ChatMode.PRODUCTIVITY_LIGHT) return true
            return !hasTagMatch(memory, classification, latestUserMessage)
        }

        private fun hasTagMatch(
            memory: PersonalMemory,
            classification: ChatClassification,
            latestUserMessage: MessageV2
        ): Boolean {
            val messageText = latestUserMessage.content.lowercase()
            val comparableTags = memory.tags.map { it.lowercase() }
            return comparableTags.any { tag ->
                tag.isNotBlank() && (tag in classification.domains || tag in classification.entities || messageText.contains(tag))
            }
        }

        private fun irrelevantModePenalty(
            memory: PersonalMemory,
            classification: ChatClassification
        ): Float = when {
            classification.mode != ChatMode.PRODUCTIVITY_LIGHT && memory.type == MemoryType.LIGHT_PRODUCTIVITY_PREFERENCE -> 3.5f
            classification.mode == ChatMode.CASUAL_CHAT && memory.type == MemoryType.IMPORTANT_EVENT -> 3.5f
            classification.mode == ChatMode.CASUAL_CHAT && memory.type == MemoryType.LIFE_CONTEXT -> 2.5f
            else -> 0f
        }

        private fun memoryNeedsForMode(mode: String): List<String> = when (mode) {
            ChatMode.EMOTIONAL_SUPPORT -> listOf(
                MemoryType.COMMUNICATION_STYLE,
                MemoryType.STABLE_PROFILE,
                MemoryType.IMPORTANT_EVENT,
                MemoryType.LIFE_CONTEXT,
                MemoryType.EMOTIONAL_PATTERN,
                MemoryType.BOUNDARY
            )

            ChatMode.PERSONAL_UPDATE,
            ChatMode.ADVICE_SEEKING,
            ChatMode.RELATIONSHIP_TALK -> listOf(
                MemoryType.COMMUNICATION_STYLE,
                MemoryType.STABLE_PROFILE,
                MemoryType.IMPORTANT_EVENT,
                MemoryType.LIFE_CONTEXT,
                MemoryType.IMPORTANT_PERSON,
                MemoryType.BOUNDARY
            )

            ChatMode.PRODUCTIVITY_LIGHT -> listOf(
                MemoryType.COMMUNICATION_STYLE,
                MemoryType.STABLE_PROFILE,
                MemoryType.LIGHT_PRODUCTIVITY_PREFERENCE,
                MemoryType.BOUNDARY
            )

            ChatMode.INTEREST_CHAT,
            ChatMode.CREATIVE_PLAY,
            ChatMode.LEARNING_LIGHT,
            ChatMode.CASUAL_CHAT -> listOf(
                MemoryType.COMMUNICATION_STYLE,
                MemoryType.STABLE_PROFILE,
                MemoryType.INTEREST,
                MemoryType.BOUNDARY
            )

            else -> listOf(MemoryType.COMMUNICATION_STYLE, MemoryType.STABLE_PROFILE, MemoryType.BOUNDARY)
        }

        private fun relevantTypesForMode(mode: String): List<String> = memoryNeedsForMode(mode) + when (mode) {
            ChatMode.EMOTIONAL_SUPPORT -> listOf(MemoryType.IMPORTANT_EVENT)
            ChatMode.PERSONAL_UPDATE, ChatMode.ADVICE_SEEKING -> listOf(MemoryType.RECURRING_THEME)
            else -> emptyList()
        }

        private fun extractDomains(text: String): List<String> = buildSet {
            if (text.containsAny(PRODUCTIVITY_KEYWORDS)) add("productivity")
            if (text.containsAny(listOf("考试", "面试", "学习", "课程", "作业"))) add("study")
            if (text.containsAny(listOf("搬家", "家人", "朋友", "恋爱", "关系"))) add("life")
            if (text.containsAny(listOf("写作", "故事", "画", "创作"))) add("creative")
        }.toList()

        private fun extractSimpleEntities(text: String): List<String> = extractTags(text)

        private fun extractTags(text: String): List<String> = buildSet {
            TAG_KEYWORDS.forEach { tag ->
                if (text.contains(tag, ignoreCase = true)) add(tag.lowercase())
            }
        }.toList()

        private fun String.containsAny(keywords: List<String>): Boolean = keywords.any { contains(it, ignoreCase = true) }

        private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

        private val EMOTIONAL_KEYWORDS = listOf("难受", "難受", "撑不住", "撐不住", "焦虑", "焦慮", "崩溃", "崩潰", "压力", "壓力", "沮丧", "失落")
        private val PERSONAL_UPDATE_KEYWORDS = listOf("最近", "今天", "明天", "考试", "考試", "搬家", "面试", "面試", "家人", "生病")
        private val ADVICE_KEYWORDS = listOf("怎么办", "怎麼辦", "怎么", "如何", "建议", "建議", "该不该", "要不要", "你觉得")
        private val CASUAL_KEYWORDS = listOf("你觉得", "聊聊", "随便聊", "隨便聊", "轻松", "輕鬆")
        private val PRODUCTIVITY_KEYWORDS = listOf("代码", "代碼", "项目", "專案", "Kotlin", "Android", "bug", "调试", "debug")
        private val RELATIONSHIP_KEYWORDS = listOf("朋友", "恋爱", "戀愛", "关系", "關係", "同事", "家人")
        private val CREATIVE_KEYWORDS = listOf("故事", "创作", "創作", "写作", "畫", "画")
        private val LEARNING_KEYWORDS = listOf("学习", "學習", "课程", "課程", "解释", "解釋")
        private val COMMUNICATION_STYLE_LEARN_KEYWORDS = listOf("以后用中文", "用中文", "我喜欢", "我喜歡", "我不喜欢", "我不喜歡", "别太说教", "別太說教", "不要太说教", "别再", "別再", "不要再")
        private val BOUNDARY_KEYWORDS = listOf("别", "別", "不要", "不喜欢", "不喜歡", "边界", "界限", "说教", "說教")
        private val IMPORTANT_EVENT_LEARN_KEYWORDS = listOf("明天考试", "明天考試", "准备面试", "準備面試", "家人生病", "要搬家", "最近在准备", "最近在準備")
        private val LIFE_CONTEXT_KEYWORDS = listOf("家人", "搬家", "生病", "生活", "最近")
        private val SENSITIVE_KEYWORDS = listOf("身份证", "身分證", "银行卡", "銀行卡", "密码", "密碼", "病历", "病歷", "诊断", "診斷", "自杀", "自殺")
        private val TAG_KEYWORDS = listOf(
            "中文",
            "考试",
            "考試",
            "面试",
            "面試",
            "搬家",
            "家人",
            "Kotlin",
            "Android",
            "bug",
            "代码",
            "项目",
            "说教",
            "說教"
        )
    }
}

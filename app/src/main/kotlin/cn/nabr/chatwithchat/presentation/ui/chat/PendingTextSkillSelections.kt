package cn.nabr.chatwithchat.presentation.ui.chat

import cn.nabr.chatwithchat.data.textskill.PendingTextSkillSelection
import cn.nabr.chatwithchat.data.textskill.SelectedTextSkill
import cn.nabr.chatwithchat.data.textskill.TextSkillSelectionScope
import cn.nabr.chatwithchat.data.textskill.selectionKey
import kotlinx.serialization.json.Json

/**
 * Skill picks that are held outside the database, either because the chat row does not exist yet
 * (landing page, or a chat whose first message has not been sent) or because they are one-shot.
 *
 * The merge rules mirror `ChatViewModel`: a one-shot pick replaces the previous one-shot set, a
 * session pick is appended and de-duplicated against the session picks already held.
 */
internal fun mergeTextSkillSelection(
    current: List<PendingTextSkillSelection>,
    skills: List<SelectedTextSkill>,
    scope: TextSkillSelectionScope
): List<PendingTextSkillSelection> = when (scope) {
    TextSkillSelectionScope.ONE_SHOT -> current.filter { it.scope != TextSkillSelectionScope.ONE_SHOT } +
        skills.distinctBy(SelectedTextSkill::selectionKey).map { skill ->
            PendingTextSkillSelection(skill = skill, scope = TextSkillSelectionScope.ONE_SHOT)
        }

    TextSkillSelectionScope.SESSION -> {
        val existingKeys = current
            .filter { it.scope == TextSkillSelectionScope.SESSION }
            .mapTo(mutableSetOf()) { it.skill.selectionKey() }
        current + skills
            .distinctBy(SelectedTextSkill::selectionKey)
            .filterNot { skill -> skill.selectionKey() in existingKeys }
            .map { skill -> PendingTextSkillSelection(skill = skill, scope = TextSkillSelectionScope.SESSION) }
    }
}

internal fun List<PendingTextSkillSelection>.removeTextSkillSelection(
    skill: SelectedTextSkill
): List<PendingTextSkillSelection> = filterNot { selection ->
    selection.skill.selectionKey() == skill.selectionKey()
}

internal fun List<PendingTextSkillSelection>.skillsInScope(
    scope: TextSkillSelectionScope
): List<SelectedTextSkill> = filter { it.scope == scope }.map(PendingTextSkillSelection::skill)

internal fun encodePendingTextSkillSelections(selections: List<PendingTextSkillSelection>): String =
    if (selections.isEmpty()) "" else PENDING_TEXT_SKILL_JSON.encodeToString(selections)

internal fun decodePendingTextSkillSelections(encoded: String): List<PendingTextSkillSelection> {
    if (encoded.isBlank()) return emptyList()
    return runCatching {
        PENDING_TEXT_SKILL_JSON.decodeFromString<List<PendingTextSkillSelection>>(encoded)
    }.getOrDefault(emptyList())
}

private val PENDING_TEXT_SKILL_JSON = Json { ignoreUnknownKeys = true }

package dev.chungjungsoo.gptmobile.data.database.entity

object MemoryType {
    const val STABLE_PROFILE = "stable_profile"
    const val COMMUNICATION_STYLE = "communication_style"
    const val INTEREST = "interest"
    const val IMPORTANT_EVENT = "important_event"
    const val IMPORTANT_PERSON = "important_person"
    const val EMOTIONAL_PATTERN = "emotional_pattern"
    const val BOUNDARY = "boundary"
    const val LIFE_CONTEXT = "life_context"
    const val RECURRING_THEME = "recurring_theme"
    const val LIGHT_PRODUCTIVITY_PREFERENCE = "light_productivity_preference"
}

object MemoryScope {
    const val GLOBAL = "global"
    const val PERSONAL = "personal"
    const val DOMAIN = "domain"
    const val CHAT = "chat"
}

object MemorySource {
    const val EXPLICIT_USER_STATEMENT = "explicit_user_statement"
    const val INFERRED = "inferred"
    const val USER_CONFIRMED = "user_confirmed"
}

object MemorySensitivity {
    const val NORMAL = "normal"
    const val PRIVATE = "private"
    const val SENSITIVE = "sensitive"
}

object MemoryStatus {
    const val ACTIVE = "active"
    const val RESOLVED = "resolved"
    const val SUPERSEDED = "superseded"
    const val ARCHIVED = "archived"
}

object ChatMode {
    const val CASUAL_CHAT = "casual_chat"
    const val PERSONAL_UPDATE = "personal_update"
    const val EMOTIONAL_SUPPORT = "emotional_support"
    const val ADVICE_SEEKING = "advice_seeking"
    const val RELATIONSHIP_TALK = "relationship_talk"
    const val INTEREST_CHAT = "interest_chat"
    const val CREATIVE_PLAY = "creative_play"
    const val LEARNING_LIGHT = "learning_light"
    const val PRODUCTIVITY_LIGHT = "productivity_light"
}

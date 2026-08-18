package cn.nabr.chatwithchat.data.textskill

object TextSkillLimits {
    const val VERSION = 1
    const val MAX_COMPRESSED_ZIP_BYTES = 4 * 1024 * 1024L
    const val MAX_UNCOMPRESSED_ZIP_BYTES = 16 * 1024 * 1024L
    const val MAX_ZIP_ENTRIES = 512
    const val MAX_SINGLE_FILE_BYTES = 512 * 1024
    const val MAX_ENTRY_BODY_BYTES = 128 * 1024
    const val MAX_REFERENCE_FILE_BYTES = 128 * 1024
    const val MAX_DIRECT_REFERENCES_PER_SKILL = 12
    const val MAX_SKILLS_PER_REQUEST = 8
    const val MAX_REQUEST_TEXT_BYTES = 512 * 1024
    const val MAX_REQUEST_ESTIMATED_TOKENS = 96_000
}

package cn.nabr.chatwithchat.data.context

/**
 * Per-provider context strategy, persisted on `PlatformV2`.
 *
 * The two values differ only in *preference*, not in capability:
 *
 *  - [AUTO_COMPACT] always assembles a request from the saved checkpoint plus a recent raw tail.
 *  - [RAW_PREFERRED] sends selected turns verbatim while they fit the effective input budget, and
 *    falls back to compacting the earlier ones once they no longer do.
 *
 * [RAW_PREFERRED] therefore means "raw while capacity allows", **not** "never compact". Nothing here
 * ever discards a message: only the request assembly changes.
 */
enum class ContextStrategy {
    AUTO_COMPACT,
    RAW_PREFERRED;

    companion object {
        val DEFAULT: ContextStrategy = AUTO_COMPACT

        /** Tolerant parse for the persisted column; unknown or legacy values fall back to default. */
        fun fromStorage(value: String?): ContextStrategy =
            entries.firstOrNull { it.name == value?.trim()?.uppercase() } ?: DEFAULT
    }
}

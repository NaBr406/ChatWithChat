package cn.nabr.chatwithchat.data.notification

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Xiaomi's OS3 notification template. Contains status only, never conversation content. */
internal object HyperOsGenerationParams {
    const val PARAM_KEY = "miui.focus.param"
    const val PICS_KEY = "miui.focus.pics"
    const val ACTIONS_KEY = "miui.focus.actions"
    const val ICON_KEY = "miui.focus.pic_generation"
    const val STOP_KEY = "miui.focus.action_stop_generation"

    fun build(title: String, summary: String, compactTitle: String, canStop: Boolean): String = buildJsonObject {
        putJsonObject("param_v2") {
            put("protocol", 1)
            put("business", "chat_generation")
            put("updatable", true)
            put("reopen", "reopen")
            put("islandFirstFloat", false)
            put("enableFloat", false)
            put("filterWhenNoPermission", false)
            put("ticker", compactTitle)
            put("aodTitle", title)
            putJsonObject("baseInfo") {
                put("type", 2)
                put("title", title)
                put("content", summary)
            }
            putJsonObject("param_island") {
                put("islandProperty", 1)
                put("islandOrder", false)
                // Match the foreground dataSync task horizon, including long continuation runs.
                put("islandTimeout", 6 * 60 * 60)
                val picture = buildJsonObject {
                    put("type", 1)
                    put("pic", ICON_KEY)
                }
                putJsonObject("bigIslandArea") {
                    putJsonObject("imageTextInfoLeft") {
                        put("type", 1)
                        // textInfo is specified in the template PDF (pp. 95–96).
                        putJsonObject("textInfo") {
                            put("title", compactTitle)
                            put("showHighlightColor", false)
                        }
                    }
                    put("picInfo", picture)
                }
                putJsonObject("smallIslandArea") {
                    put("picInfo", picture)
                }
            }
            if (canStop) {
                put(
                    "actions",
                    buildJsonArray {
                        add(buildJsonObject { put("action", STOP_KEY) })
                    }
                )
            }
        }
    }.toString()
}

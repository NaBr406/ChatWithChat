package cn.nabr.chatwithchat.data.dto

import cn.nabr.chatwithchat.data.ModelConstants.getDefaultAPIUrl
import cn.nabr.chatwithchat.data.model.ApiType

data class Platform(
    val name: ApiType,
    val selected: Boolean = false,
    val enabled: Boolean = false,
    val apiUrl: String = getDefaultAPIUrl(name),
    val token: String? = null,
    val model: String? = null,
    val temperature: Float? = null,
    val topP: Float? = null,
    val systemPrompt: String? = null
)

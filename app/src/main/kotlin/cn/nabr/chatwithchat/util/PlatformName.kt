package cn.nabr.chatwithchat.util

import cn.nabr.chatwithchat.data.database.entity.PlatformV2

fun List<PlatformV2>.getPlatformName(uid: String): String = this.find { it.uid == uid }?.name ?: "未知"

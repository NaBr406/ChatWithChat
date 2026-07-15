package cn.nabr.chatwithchat.data.dto

import cn.nabr.chatwithchat.data.model.DynamicTheme
import cn.nabr.chatwithchat.data.model.ThemeMode

data class ThemeSetting(
    val dynamicTheme: DynamicTheme = DynamicTheme.OFF,
    val themeMode: ThemeMode = ThemeMode.SYSTEM
)

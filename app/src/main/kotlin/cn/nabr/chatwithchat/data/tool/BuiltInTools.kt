package cn.nabr.chatwithchat.data.tool

import cn.nabr.chatwithchat.data.websearch.WebPageExtractor
import cn.nabr.chatwithchat.data.websearch.WebSearchRepository

class BuiltInTools(
    private val webSearchRepository: WebSearchRepository,
    private val webPageExtractor: WebPageExtractor,
    private val deviceLocationReader: DeviceLocationReader = UnavailableDeviceLocationReader,
    private val scheduleEventLauncher: ScheduleEventLauncher = UnavailableScheduleEventLauncher,
    private val alarmLauncher: AlarmLauncher = UnavailableAlarmLauncher
) {
    fun providers(): List<ToolProvider> = listOf(
        WebSearchToolProvider(webSearchRepository),
        FetchUrlToolProvider(webPageExtractor),
        CurrentDateTimeToolProvider(),
        DeviceLocationToolProvider(deviceLocationReader),
        AddScheduleToolProvider(scheduleEventLauncher),
        SetAlarmToolProvider(alarmLauncher)
    )

    fun registry(): ToolRegistry = ToolRegistry(providers())
}

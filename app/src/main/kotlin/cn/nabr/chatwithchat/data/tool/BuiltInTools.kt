package cn.nabr.chatwithchat.data.tool

import cn.nabr.chatwithchat.data.sticker.StickerRepository
import cn.nabr.chatwithchat.data.websearch.WebPageExtractor
import cn.nabr.chatwithchat.data.websearch.WebSearchRepository

class BuiltInTools(
    private val webSearchRepository: WebSearchRepository,
    private val webPageExtractor: WebPageExtractor,
    private val deviceLocationReader: DeviceLocationReader = UnavailableDeviceLocationReader,
    private val scheduleEventLauncher: ScheduleEventLauncher = UnavailableScheduleEventLauncher,
    private val alarmLauncher: AlarmLauncher = UnavailableAlarmLauncher,
    private val stickerRepository: StickerRepository? = null
) {
    fun providers(): List<ToolProvider> = buildList {
        add(WebSearchToolProvider(webSearchRepository))
        add(FetchUrlToolProvider(webPageExtractor))
        add(CurrentDateTimeToolProvider())
        add(DeviceLocationToolProvider(deviceLocationReader))
        add(AddScheduleToolProvider(scheduleEventLauncher))
        add(SetAlarmToolProvider(alarmLauncher))
        stickerRepository?.let { repository ->
            add(SearchStickersToolProvider(repository))
            add(SendStickerToolProvider(repository))
        }
    }

    fun registry(): ToolRegistry = ToolRegistry(providers())
}

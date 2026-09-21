package cn.nabr.chatwithchat.data.tool

import cn.nabr.chatwithchat.data.sticker.StickerRepository
import cn.nabr.chatwithchat.data.memory.InMemoryMemoryProposalStore
import cn.nabr.chatwithchat.data.memory.MemoryProposalStore
import cn.nabr.chatwithchat.data.websearch.WebPageExtractor
import cn.nabr.chatwithchat.data.websearch.WebSearchRepository
import cn.nabr.chatwithchat.data.visual.VisualAssetRepository

class BuiltInTools(
    private val webSearchRepository: WebSearchRepository,
    private val webPageExtractor: WebPageExtractor,
    private val deviceLocationReader: DeviceLocationReader = UnavailableDeviceLocationReader,
    private val scheduleEventLauncher: ScheduleEventLauncher = UnavailableScheduleEventLauncher,
    private val alarmLauncher: AlarmLauncher = UnavailableAlarmLauncher,
    private val stickerRepository: StickerRepository? = null,
    private val memoryProposalStore: MemoryProposalStore = InMemoryMemoryProposalStore(),
    private val visualAssetRepository: VisualAssetRepository? = null,
    private val askUserQuestionBroker: AskUserQuestionBroker = AskUserQuestionBroker()
) {
    fun providers(): List<ToolProvider> = buildList {
        add(WebSearchToolProvider(webSearchRepository))
        add(FetchUrlToolProvider(webPageExtractor))
        add(CurrentDateTimeToolProvider())
        add(DeviceLocationToolProvider(deviceLocationReader))
        add(AddScheduleToolProvider(scheduleEventLauncher))
        add(SetAlarmToolProvider(alarmLauncher))
        add(ProposeMemoryChangeToolProvider(memoryProposalStore))
        add(AskUserQuestionToolProvider(askUserQuestionBroker))
        stickerRepository?.let { repository ->
            add(SearchStickersToolProvider(repository))
            add(SendStickerToolProvider(repository))
        }
        visualAssetRepository?.let { repository ->
            add(ListVisualAssetsToolProvider(repository))
            add(ViewImageToolProvider(repository))
        }
    }

    fun registry(): ToolRegistry = ToolRegistry(providers())
}

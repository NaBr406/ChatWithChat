package cn.nabr.chatwithchat.data.memory

import java.time.Clock

interface MemoryVectorRecallRepairTrigger {
    fun requestRepair()
}

class WorkEnqueuingMemoryVectorRecallRepairTrigger(
    private val workEnqueuer: MemoryMaintenanceWorkEnqueuer,
    private val clock: Clock = Clock.systemUTC(),
    private val minimumIntervalSeconds: Long = DEFAULT_MINIMUM_INTERVAL_SECONDS
) : MemoryVectorRecallRepairTrigger {
    private var lastRequestedAt: Long? = null

    init {
        require(minimumIntervalSeconds >= 0) { "minimumIntervalSeconds must not be negative" }
    }

    @Synchronized
    override fun requestRepair() {
        val now = clock.instant().epochSecond
        val previous = lastRequestedAt
        if (previous != null && now - previous < minimumIntervalSeconds) return
        workEnqueuer.enqueueWork(MemoryMaintenanceJobFamily.REPAIR)
        lastRequestedAt = now
    }

    private companion object {
        const val DEFAULT_MINIMUM_INTERVAL_SECONDS = 60L
    }
}

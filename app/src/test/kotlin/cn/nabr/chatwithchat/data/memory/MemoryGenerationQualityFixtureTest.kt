package cn.nabr.chatwithchat.data.memory

import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryGenerationQualityFixtureTest {
    private val json = Json { ignoreUnknownKeys = false }

    @Test
    fun `fixed generation fixture produces a comparable baseline report`() {
        val fixture = loadFixture()
        val report = MemoryGenerationQualityReport.from(fixture.cases)

        println("memory-generation-quality-baseline|$report")

        assertEquals(0.8571, report.durablePrecision, 0.0001)
        assertEquals(0.4, report.unnecessaryCreationRate, 0.0001)
        assertEquals(0.1, report.activeDuplicateRate, 0.0001)
        assertEquals(1, report.correctionConvergenceTurns)
        assertEquals(0.1, report.staleMemoryRate, 0.0001)
        assertEquals(1.0, report.evidenceCoverage, 0.0001)
        assertEquals(0.2, report.assistantInferenceCoreRate, 0.0001)
        assertEquals(0.0, report.privateLeakageRate, 0.0001)
        assertEquals(1.0, report.recallUsefulnessRate, 0.0001)
        assertTrue(fixture.cases.map(FixtureCase::id).distinct().size == fixture.cases.size)
    }

    private fun loadFixture(): Fixture = checkNotNull(
        javaClass.classLoader?.getResourceAsStream(FIXTURE_RESOURCE)
    ) { "missing $FIXTURE_RESOURCE" }.use { stream ->
        json.decodeFromString(stream.readBytes().toString(Charsets.UTF_8))
    }

    private companion object {
        const val FIXTURE_RESOURCE = "memory_generation_quality_fixture.json"
    }
}

@Serializable
private data class Fixture(
    val cases: List<FixtureCase>
)

@Serializable
private data class FixtureCase(
    val id: String,
    val expectedAction: String,
    val modelAction: String,
    val memoryClass: String,
    val source: String,
    val canonicalKey: String,
    val scope: String,
    val recallState: String,
    val sensitivity: String,
    val evidenceDays: Int,
    val expectedRecall: Boolean,
    val modelRecall: Boolean,
    val convergenceTurns: Int? = null
)

private data class MemoryGenerationQualityReport(
    val durablePrecision: Double,
    val unnecessaryCreationRate: Double,
    val activeDuplicateRate: Double,
    val correctionConvergenceTurns: Int,
    val staleMemoryRate: Double,
    val evidenceCoverage: Double,
    val assistantInferenceCoreRate: Double,
    val privateLeakageRate: Double,
    val recallUsefulnessRate: Double
) {
    override fun toString(): String = listOf(
        "durable_precision=$durablePrecision",
        "unnecessary_creation_rate=$unnecessaryCreationRate",
        "active_duplicate_rate=$activeDuplicateRate",
        "correction_convergence_turns=$correctionConvergenceTurns",
        "stale_memory_rate=$staleMemoryRate",
        "evidence_coverage=$evidenceCoverage",
        "assistant_inference_core_rate=$assistantInferenceCoreRate",
        "private_leakage_rate=$privateLeakageRate",
        "recall_usefulness_rate=$recallUsefulnessRate"
    ).joinToString("|")

    companion object {
        fun from(cases: List<FixtureCase>): MemoryGenerationQualityReport {
            val writes = cases.filter { it.modelAction in WRITE_ACTIONS }
            val durableWrites = writes.filter { it.memoryClass == "durable_fact" }
            val unnecessaryWrites = writes.count { it.expectedAction !in DURABLE_ACTIONS }
            val activeKeys = writes
                .filter { it.recallState in ACTIVE_RECALL_STATES }
                .groupingBy { it.canonicalKey to it.scope }
                .eachCount()
            val duplicateEntries = activeKeys.values.sumOf { count -> (count - 1).coerceAtLeast(0) }
            val privateCases = cases.filter { it.sensitivity == "private" }
            val coreEntries = cases.filter { it.recallState == "core" }
            val inferredCoreEntries = coreEntries.count { it.source == "assistant_inferred" }
            val usefulRecall = cases.count { it.expectedRecall && it.modelRecall }
            val expectedRecallCases = cases.count(FixtureCase::expectedRecall)
            return MemoryGenerationQualityReport(
                durablePrecision = ratio(
                    durableWrites.count { it.expectedAction in DURABLE_ACTIONS },
                    durableWrites.size
                ),
                unnecessaryCreationRate = ratio(unnecessaryWrites, writes.size),
                activeDuplicateRate = ratio(duplicateEntries, writes.size),
                correctionConvergenceTurns = cases.first { it.id == "correction" }.convergenceTurns ?: -1,
                staleMemoryRate = ratio(
                    cases.count { it.id == "stale_project_state" && it.modelAction in WRITE_ACTIONS },
                    cases.size
                ),
                evidenceCoverage = ratio(writes.count { it.evidenceDays > 0 }, writes.size),
                assistantInferenceCoreRate = ratio(inferredCoreEntries, coreEntries.size),
                privateLeakageRate = ratio(
                    privateCases.count { it.modelRecall },
                    privateCases.size
                ),
                recallUsefulnessRate = ratio(usefulRecall, expectedRecallCases)
            )
        }

        private fun ratio(numerator: Int, denominator: Int): Double = if (denominator == 0) {
            0.0
        } else {
            BigDecimal(numerator)
                .divide(BigDecimal(denominator), 4, RoundingMode.HALF_UP)
                .toDouble()
        }

        private val WRITE_ACTIONS = setOf("CREATE", "REPLACE")
        private val DURABLE_ACTIONS = setOf("KEEP_DURABLE", "KEEP_ACTIVE_PROJECT", "MERGE")
        private val ACTIVE_RECALL_STATES = setOf("core", "query")
    }
}

package cn.nabr.chatwithchat.presentation.ui.chat

import cn.nabr.chatwithchat.data.textskill.InstalledSkillPackage
import cn.nabr.chatwithchat.data.textskill.PendingTextSkillSelection
import cn.nabr.chatwithchat.data.textskill.RegisteredTextSkillVariant
import cn.nabr.chatwithchat.data.textskill.SelectedTextSkill
import cn.nabr.chatwithchat.data.textskill.SkillFrontmatter
import cn.nabr.chatwithchat.data.textskill.SkillStandardStatus
import cn.nabr.chatwithchat.data.textskill.TextSkillCompatibility
import cn.nabr.chatwithchat.data.textskill.TextSkillSelectionScope
import cn.nabr.chatwithchat.data.textskill.toSelectableTextSkills
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingTextSkillSelectionsTest {
    @Test
    fun oneShotSelection_replacesPreviousOneShotAndKeepsSessionPicks() {
        val current = listOf(
            PendingTextSkillSelection(skill("review"), TextSkillSelectionScope.SESSION),
            PendingTextSkillSelection(skill("translate"), TextSkillSelectionScope.ONE_SHOT)
        )

        val merged = mergeTextSkillSelection(
            current = current,
            skills = listOf(skill("summarize")),
            scope = TextSkillSelectionScope.ONE_SHOT
        )

        assertEquals(listOf("review"), merged.skillsInScope(TextSkillSelectionScope.SESSION).map { it.skillId })
        assertEquals(listOf("summarize"), merged.skillsInScope(TextSkillSelectionScope.ONE_SHOT).map { it.skillId })
    }

    @Test
    fun sessionSelection_appendsAndDeduplicatesAgainstExistingSessionPicks() {
        val current = listOf(PendingTextSkillSelection(skill("review"), TextSkillSelectionScope.SESSION))

        val merged = mergeTextSkillSelection(
            current = current,
            skills = listOf(skill("review"), skill("translate"), skill("translate")),
            scope = TextSkillSelectionScope.SESSION
        )

        assertEquals(
            listOf("review", "translate"),
            merged.skillsInScope(TextSkillSelectionScope.SESSION).map { it.skillId }
        )
    }

    @Test
    fun sameSkillInBothScopes_isKeptTwiceBecauseTheScopesOutliveEachOtherDifferently() {
        val oneShot = mergeTextSkillSelection(
            current = emptyList(),
            skills = listOf(skill("review")),
            scope = TextSkillSelectionScope.ONE_SHOT
        )

        val merged = mergeTextSkillSelection(
            current = oneShot,
            skills = listOf(skill("review")),
            scope = TextSkillSelectionScope.SESSION
        )

        assertEquals(2, merged.size)
        assertEquals(listOf("review"), merged.skillsInScope(TextSkillSelectionScope.ONE_SHOT).map { it.skillId })
        assertEquals(listOf("review"), merged.skillsInScope(TextSkillSelectionScope.SESSION).map { it.skillId })
    }

    @Test
    fun removal_dropsEveryScopeOfTheSameVariant() {
        val selections = listOf(
            PendingTextSkillSelection(skill("review"), TextSkillSelectionScope.ONE_SHOT),
            PendingTextSkillSelection(skill("review"), TextSkillSelectionScope.SESSION),
            PendingTextSkillSelection(skill("translate"), TextSkillSelectionScope.SESSION)
        )

        val remaining = selections.removeTextSkillSelection(skill("review"))

        assertEquals(listOf("translate"), remaining.map { it.skill.skillId })
    }

    @Test
    fun removal_matchesOnVariantIdentityRatherThanDisplayName() {
        val selections = listOf(PendingTextSkillSelection(skill("review"), TextSkillSelectionScope.SESSION))

        val remaining = selections.removeTextSkillSelection(skill("review").copy(displayName = "renamed"))

        assertTrue(remaining.isEmpty())
    }

    @Test
    fun encodedSelections_surviveARoundTripWithTheirScopeAndEnabledFlag() {
        val selections = listOf(
            PendingTextSkillSelection(skill("review"), TextSkillSelectionScope.SESSION, isEnabled = false),
            PendingTextSkillSelection(skill("translate"), TextSkillSelectionScope.ONE_SHOT)
        )

        val decoded = decodePendingTextSkillSelections(encodePendingTextSkillSelections(selections))

        assertEquals(selections, decoded)
    }

    @Test
    fun emptyAndCorruptPayloads_decodeToNoSelections() {
        assertEquals("", encodePendingTextSkillSelections(emptyList()))
        assertTrue(decodePendingTextSkillSelections("").isEmpty())
        assertTrue(decodePendingTextSkillSelections("   ").isEmpty())
        assertTrue(decodePendingTextSkillSelections("not json").isEmpty())
    }

    @Test
    fun installedPackages_flattenIntoEverySelectableVariant() {
        val packages = listOf(
            installedPackage("cn.pkg.review", "review", "translate"),
            installedPackage("cn.pkg.write", "outline")
        )

        val selectable = packages.toSelectableTextSkills()

        assertEquals(
            listOf(
                "cn.pkg.review:review:default",
                "cn.pkg.review:translate:default",
                "cn.pkg.write:outline:default"
            ),
            selectable.map { "${it.packageId}:${it.skillId}:${it.variantKey}" }
        )
        assertEquals(listOf("review", "translate", "outline"), selectable.map { it.displayName })
    }

    @Test
    fun unsavedSessionBinding_carriesTheEnabledFlagAndOrderOfThePick() {
        val binding = PendingTextSkillSelection(skill("review"), TextSkillSelectionScope.SESSION, isEnabled = false)
            .toSessionBinding(chatId = 0, orderIndex = 3)

        assertEquals(0, binding.chatId)
        assertEquals("cn.pkg:review:default", binding.selectionKey())
        assertEquals(3, binding.orderIndex)
        assertEquals(false, binding.isEnabled)
        assertEquals("review", binding.displayName)
    }

    private fun skill(skillId: String): SelectedTextSkill = SelectedTextSkill(
        packageId = "cn.pkg",
        skillId = skillId,
        variantKey = "default",
        displayName = skillId
    )

    private fun installedPackage(packageId: String, vararg skillIds: String): InstalledSkillPackage =
        InstalledSkillPackage(
            packageId = packageId,
            sourceName = "$packageId.zip",
            variants = skillIds.map { skillId ->
                RegisteredTextSkillVariant(
                    skillId = skillId,
                    variantKey = "default",
                    packageRelativeDir = ".",
                    displayName = skillId,
                    entryPath = "SKILL.md",
                    name = skillId,
                    description = skillId,
                    frontmatter = SkillFrontmatter(name = skillId, description = skillId),
                    standardStatus = SkillStandardStatus.STANDARD,
                    textCompatibility = TextSkillCompatibility.TEXT_ONLY,
                    contentHash = "hash-$skillId"
                )
            },
            hasScripts = false,
            packageHash = "hash-$packageId"
        )
}

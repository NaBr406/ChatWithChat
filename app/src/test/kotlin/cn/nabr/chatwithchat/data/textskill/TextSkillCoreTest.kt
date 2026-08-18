package cn.nabr.chatwithchat.data.textskill

import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSkillCoreTest {
    @Test
    fun discovery_rootAndNestedSkill_registersOnlyParentEntry() {
        val root = temporaryDirectory()
        root.resolve("SKILL.md").writeText(standardSkill("root"))
        root.resolve("versions/current").mkdirs()
        root.resolve("versions/current/SKILL.md").writeText(standardSkill("nested"))

        val variants = AgentSkillDiscovery(adapters = emptyList()).discover(root)

        assertEquals(1, variants.size)
        assertEquals("default", variants.single().variantKey)
        assertEquals("SKILL.md", variants.single().entryFile.name)
    }

    @Test
    fun discovery_dotSkillManifest_expandsOnlyDeclaredMarkdownEntrypoints() {
        val root = temporaryDirectory()
        root.resolve("SKILL.md").writeText(standardSkill("base"))
        root.resolve("work.md").writeText(standardSkill("work"))
        root.resolve("ignored.md").writeText(standardSkill("ignored"))
        root.resolve("manifest.json").writeText(
            """{"manifest_version":"1","kind":"meta-skill","engine":{"name":"dot-skill"},"entrypoints":{"work":"work.md"}}"""
        )

        val variants = AgentSkillDiscovery().discover(root)

        assertEquals(listOf("default", "work"), variants.map { it.variantKey })
        assertFalse(variants.any { it.entryFile.name == "ignored.md" })
    }

    @Test
    fun frontmatter_standardClaudeAndInvalid_areClassified() {
        val parser = SkillFrontmatterParser()

        assertEquals(SkillStandardStatus.STANDARD, parser.parse(standardSkill("review")).status)
        assertEquals(
            SkillStandardStatus.CLAUDE_COMPATIBLE,
            parser.parse("---\ndescription: Claude compatible\n---\nBody").status
        )
        assertEquals(
            SkillStandardStatus.NON_STANDARD_FRONTMATTER,
            parser.parse("---\nname: incomplete\n---\nBody").status
        )
    }

    @Test
    fun referenceResolver_readsOnlyDirectUtf8TextReferences() {
        val root = temporaryDirectory()
        root.resolve("references").mkdir()
        root.resolve("references/checklist.md").writeText("Checklist")
        root.resolve("references/second.md").writeText("Second")
        root.resolve("references/run.sh").writeText("rm -rf /")
        root.resolve("references/binary.md").writeBytes(byteArrayOf(0, 1, 2))
        val body = "See [check](references/checklist.md) and `references/second.md`. [script](references/run.sh) [bad](../secret.md) [url](https://example.com/x.md)"

        val references = TextSkillReferenceResolver().resolve(root, body)

        assertEquals(listOf("references/checklist.md", "references/second.md"), references.map { it.path })
        assertEquals(listOf("Checklist", "Second"), references.map { it.content })
    }

    @Test
    fun registeredReferenceHashes_detectChangedDirectReferenceContent() {
        val variant = RegisteredTextSkillVariant(
            skillId = "skill",
            variantKey = "default",
            packageRelativeDir = ".",
            displayName = "Review",
            entryPath = "SKILL.md",
            name = "Review",
            description = "Review text",
            frontmatter = SkillFrontmatter(name = "Review", description = "Review text"),
            standardStatus = SkillStandardStatus.STANDARD,
            textCompatibility = TextSkillCompatibility.TEXT_ONLY,
            directReferencePaths = listOf("references/checklist.md"),
            directReferenceContentHashes = mapOf("references/checklist.md" to sha256("original")),
            contentHash = sha256("entry")
        )

        assertTrue(
            referencesMatchRegisteredVariant(
                variant,
                listOf(TextSkillReference("references/checklist.md", "original", sha256("original")))
            )
        )
        assertFalse(
            referencesMatchRegisteredVariant(
                variant,
                listOf(TextSkillReference("references/checklist.md", "changed", sha256("changed")))
            )
        )
    }

    @Test
    fun normalizedRelativePath_rejectsTraversalUrisAndAbsolutePaths() {
        assertNull(normalizedRelativePath("../x.md"))
        assertNull(normalizedRelativePath("/x.md"))
        assertNull(normalizedRelativePath("C:/x.md"))
        assertNull(normalizedRelativePath("https://example.com/a.md"))
        assertEquals("references/a.md", normalizedRelativePath("references/a.md"))
    }

    @Test
    fun normalizedRelativePath_normalizesUnicodeBeforeConflictChecks() {
        assertEquals("references/café.md", normalizedRelativePath("references/cafe\u0301.md"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun zipValidator_rejectsUnixSymbolicLinks() {
        val archive = zipWithSingleFile()
        val centralDirectory = archive.indexOfSignature(0x02014b50)
        require(centralDirectory >= 0)
        archive[centralDirectory + 4] = 20
        archive[centralDirectory + 5] = 3
        archive[centralDirectory + 38] = 0
        archive[centralDirectory + 39] = 0
        archive[centralDirectory + 40] = 0xff.toByte()
        archive[centralDirectory + 41] = 0xa1.toByte()

        TextSkillZipArchiveValidator.readAndValidate(ByteArrayInputStream(archive))
    }

    @Test(expected = IllegalArgumentException::class)
    fun zipValidator_rejectsMalformedCentralDirectory() {
        TextSkillZipArchiveValidator.readAndValidate(ByteArrayInputStream(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun textOnlyGate_deniesToolAndMcpCapabilities() = runBlocking {
        val gate = TextOnlySkillCapabilityGate()
        val context = SkillInvocationContext(chatId = 1)

        assertTrue(
            gate.resolve(SkillCapabilityRequest(SkillCapability.TEXT_REFERENCE_READ, "test"), context) is SkillCapabilityDecision.Allowed
        )
        assertTrue(
            gate.resolve(SkillCapabilityRequest(SkillCapability.MCP_TOOL_CALL, "test"), context) is SkillCapabilityDecision.Denied
        )
    }

    private fun temporaryDirectory(): File = Files.createTempDirectory("text-skill-test").toFile().also { it.deleteOnExit() }

    private fun standardSkill(name: String): String = """
        ---
        name: $name
        description: $name skill
        ---
        # $name
    """.trimIndent()

    private fun zipWithSingleFile(): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("SKILL.md"))
            zip.write(standardSkill("zip").toByteArray())
            zip.closeEntry()
        }
        output.toByteArray()
    }

    private fun ByteArray.indexOfSignature(signature: Int): Int = indices.firstOrNull { index ->
        index + 4 <= size &&
            (this[index].toInt() and 0xff) == (signature and 0xff) &&
            (this[index + 1].toInt() and 0xff) == ((signature ushr 8) and 0xff) &&
            (this[index + 2].toInt() and 0xff) == ((signature ushr 16) and 0xff) &&
            (this[index + 3].toInt() and 0xff) == ((signature ushr 24) and 0xff)
    } ?: -1
}

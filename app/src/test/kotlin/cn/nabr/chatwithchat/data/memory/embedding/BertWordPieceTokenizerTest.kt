package cn.nabr.chatwithchat.data.memory.embedding

import java.io.ByteArrayInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BertWordPieceTokenizerTest {
    private val corpus = loadGoldenCorpus()
    private val tokenizer = BertWordPieceTokenizer(corpus.requiredVocabulary)

    @Test
    fun `pinned transformers goldens match all tokenization and model inputs`() {
        assertTrue("The production golden corpus must contain at least 50 fixtures", corpus.fixtures.size >= 50)

        corpus.fixtures.forEach { fixture ->
            val actual = tokenizer.encode(fixture.text, fixture.maxTokens)
            val actualTokens = tokenizer.tokenize(fixture.text).take(fixture.maxTokens - 2)

            assertEquals(fixture.name, fixture.tokens.drop(1).dropLast(1), actualTokens)
            assertArrayEquals("${fixture.name} input_ids", fixture.inputIds, actual.inputIds)
            assertArrayEquals("${fixture.name} attention_mask", fixture.attentionMask, actual.attentionMask)
            assertArrayEquals("${fixture.name} token_type_ids", fixture.tokenTypeIds, actual.tokenTypeIds)
        }
    }

    @Test
    fun `golden metadata pins the production tokenizer provenance`() {
        assertEquals("4.57.6", corpus.metadata.getValue("transformersVersion"))
        assertEquals("0.22.2", corpus.metadata.getValue("tokenizersVersion"))
        assertEquals("BertTokenizerFast", corpus.metadata.getValue("canonicalTokenizerClass"))
        assertEquals("BAAI/bge-small-zh-v1.5", corpus.metadata.getValue("tokenizerId"))
        assertEquals("7999e1d3359715c523056ef9478215996d62a620", corpus.metadata.getValue("tokenizerRevision"))
        assertEquals("45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c", corpus.metadata.getValue("vocabSha256"))
        assertEquals("48cea5d44424912a6fd1ea647bf4fe50b55ab8b1e5879c3275f80e339e8fae26", corpus.metadata.getValue("tokenizerJsonSha256"))
        assertEquals("e6f3b96db926a37d4039995fbf5ad17de158dfb8f6343d607e4dbaad18d75f5a", corpus.metadata.getValue("tokenizerConfigSha256"))
        assertEquals(corpus.fixtures.size, corpus.fixtureCount)
    }

    @Test
    fun `padding uses fixed pad id and zero attention without changing token types`() {
        val actual = tokenizer.encode("你好", maxTokens = 8, padToMaxTokens = true)

        assertArrayEquals(longArrayOf(101, 872, 1962, 102, 0, 0, 0, 0), actual.inputIds)
        assertArrayEquals(longArrayOf(1, 1, 1, 1, 0, 0, 0, 0), actual.attentionMask)
        assertArrayEquals(LongArray(8), actual.tokenTypeIds)
    }

    @Test
    fun `two token limit retains only required cls and sep tokens`() {
        val actual = tokenizer.encode("正文必须被截断", maxTokens = 2)

        assertArrayEquals(longArrayOf(101, 102), actual.inputIds)
        assertArrayEquals(longArrayOf(1, 1), actual.attentionMask)
        assertArrayEquals(longArrayOf(0, 0), actual.tokenTypeIds)
    }

    @Test
    fun `vocab reader preserves line number ids for greedy wordpiece`() {
        val vocab = MutableList(106) { index -> "[unused-test-$index]" }
        vocab[0] = "[PAD]"
        vocab[100] = "[UNK]"
        vocab[101] = "[CLS]"
        vocab[102] = "[SEP]"
        vocab[103] = "[MASK]"
        vocab[104] = "hello"
        vocab[105] = "##s"
        val input = ByteArrayInputStream(vocab.joinToString(separator = "\n").toByteArray(Charsets.UTF_8))

        val actual = BertWordPieceTokenizer.fromVocab(input).encode("hellos")

        assertArrayEquals(longArrayOf(101, 104, 105, 102), actual.inputIds)
    }

    @Test
    fun `configured production token limit cannot be exceeded`() {
        assertThrows(IllegalArgumentException::class.java) {
            tokenizer.encode("memory", maxTokens = 257)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BertWordPieceTokenizer(corpus.requiredVocabulary, maxInputTokens = 257)
        }
    }

    private fun loadGoldenCorpus(): GoldenCorpus {
        val resource = checkNotNull(javaClass.classLoader?.getResourceAsStream(GOLDEN_RESOURCE)) {
            "Missing tokenizer golden resource $GOLDEN_RESOURCE"
        }
        val root = resource.bufferedReader(Charsets.UTF_8).use { reader ->
            Json.parseToJsonElement(reader.readText()).jsonObject
        }
        val metadataObject = root.getValue("metadata").jsonObject
        val metadata = metadataObject.entries.associate { (key, value) -> key to value.jsonPrimitive.content }
        val requiredVocabulary = root.getValue("requiredVocabulary").jsonObject.entries.associate { (token, id) ->
            token to id.jsonPrimitive.int
        }
        val fixtures = root.getValue("fixtures").jsonArray.map { element ->
            val fixture = element.jsonObject
            GoldenFixture(
                name = fixture.getValue("name").jsonPrimitive.content,
                text = fixture.getValue("text").jsonPrimitive.content,
                maxTokens = fixture.getValue("maxTokens").jsonPrimitive.int,
                tokens = fixture.getValue("tokens").jsonArray.map { token -> token.jsonPrimitive.content },
                inputIds = fixture.longArray("inputIds"),
                attentionMask = fixture.longArray("attentionMask"),
                tokenTypeIds = fixture.longArray("tokenTypeIds")
            )
        }
        return GoldenCorpus(
            metadata = metadata,
            fixtureCount = metadataObject.getValue("fixtureCount").jsonPrimitive.int,
            requiredVocabulary = requiredVocabulary,
            fixtures = fixtures
        )
    }

    private fun Map<String, kotlinx.serialization.json.JsonElement>.longArray(name: String): LongArray =
        getValue(name).jsonArray.map { value -> value.jsonPrimitive.long }.toLongArray()

    private data class GoldenCorpus(
        val metadata: Map<String, String>,
        val fixtureCount: Int,
        val requiredVocabulary: Map<String, Int>,
        val fixtures: List<GoldenFixture>
    )

    private data class GoldenFixture(
        val name: String,
        val text: String,
        val maxTokens: Int,
        val tokens: List<String>,
        val inputIds: LongArray,
        val attentionMask: LongArray,
        val tokenTypeIds: LongArray
    )

    private companion object {
        const val GOLDEN_RESOURCE = "memory-model/bge-small-zh-v1.5-tokenizer-goldens.json"
    }
}

package cn.nabr.chatwithchat.data.memory

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.nabr.chatwithchat.R
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

@RunWith(AndroidJUnit4::class)
class MemoryBackupRulesInstrumentedTest {

    @Test
    fun compiledBackupRules_excludeOnlyTransientMemoryDirectories() {
        assertEquals(
            setOf(
                BackupRule("full-backup-content", "exclude", "file", "memory_store/.staging/"),
                BackupRule("full-backup-content", "exclude", "file", "memory_store/.backups/")
            ),
            readRules(R.xml.backup_rules)
        )
        assertEquals(
            setOf(
                BackupRule("cloud-backup", "exclude", "file", "memory_store/.staging/"),
                BackupRule("cloud-backup", "exclude", "file", "memory_store/.backups/"),
                BackupRule("device-transfer", "exclude", "file", "memory_store/.staging/"),
                BackupRule("device-transfer", "exclude", "file", "memory_store/.backups/")
            ),
            readRules(R.xml.data_extraction_rules)
        )
    }

    private fun readRules(resourceId: Int): Set<BackupRule> {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val parser = resources.getXml(resourceId)
        val elementStack = ArrayDeque<String>()
        val rules = linkedSetOf<BackupRule>()
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val element = parser.name
                        if (element == "include" || element == "exclude") {
                            rules += BackupRule(
                                scope = checkNotNull(elementStack.peekLast()),
                                element = element,
                                domain = checkNotNull(parser.getAttributeValue(null, "domain")),
                                path = checkNotNull(parser.getAttributeValue(null, "path"))
                            )
                        }
                        elementStack.addLast(element)
                    }
                    XmlPullParser.END_TAG -> elementStack.removeLast()
                }
                event = parser.next()
            }
        } finally {
            parser.close()
        }
        return rules
    }
}

private data class BackupRule(
    val scope: String,
    val element: String,
    val domain: String,
    val path: String
)

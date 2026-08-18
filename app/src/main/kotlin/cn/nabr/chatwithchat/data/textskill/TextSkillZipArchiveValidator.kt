package cn.nabr.chatwithchat.data.textskill

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal object TextSkillZipArchiveValidator {
    private const val END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054b50
    private const val CENTRAL_DIRECTORY_SIGNATURE = 0x02014b50
    private const val ZIP64_SENTINEL = 0xffff
    private const val ZIP64_U32_SENTINEL = 0xffffffffL
    private const val UNIX_HOST = 3
    private const val FILE_TYPE_MASK = 0xf000
    private const val SYMBOLIC_LINK_FILE_TYPE = 0xa000

    fun readAndValidate(input: InputStream): ByteArray {
        val bytes = input.readAtMost(TextSkillLimits.MAX_COMPRESSED_ZIP_BYTES)
        validateCentralDirectory(bytes)
        return bytes
    }

    private fun validateCentralDirectory(bytes: ByteArray) {
        val endOffset = findEndOfCentralDirectory(bytes)
            ?: throw IllegalArgumentException("ZIP central directory is missing or malformed.")
        val diskNumber = bytes.u16(endOffset + 4)
        val centralDirectoryDisk = bytes.u16(endOffset + 6)
        val entryCountOnDisk = bytes.u16(endOffset + 8)
        val entryCount = bytes.u16(endOffset + 10)
        val centralDirectorySize = bytes.u32(endOffset + 12)
        val centralDirectoryOffset = bytes.u32(endOffset + 16)
        val commentLength = bytes.u16(endOffset + 20)
        require(endOffset + 22 + commentLength == bytes.size) { "ZIP central directory is malformed." }
        require(diskNumber == 0 && centralDirectoryDisk == 0 && entryCountOnDisk == entryCount) {
            "Multi-disk ZIP archives are not supported."
        }
        require(entryCount != ZIP64_SENTINEL && centralDirectorySize != ZIP64_U32_SENTINEL && centralDirectoryOffset != ZIP64_U32_SENTINEL) {
            "ZIP64 archives are not supported."
        }
        require(entryCount <= TextSkillLimits.MAX_ZIP_ENTRIES) { "ZIP contains too many entries." }
        require(centralDirectoryOffset + centralDirectorySize <= endOffset.toLong()) {
            "ZIP central directory is malformed."
        }

        var offset = centralDirectoryOffset.toInt()
        repeat(entryCount) {
            require(offset + 46 <= endOffset && bytes.u32(offset) == CENTRAL_DIRECTORY_SIGNATURE.toLong()) {
                "ZIP central directory is malformed."
            }
            val versionMadeBy = bytes.u16(offset + 4)
            val fileNameLength = bytes.u16(offset + 28)
            val extraLength = bytes.u16(offset + 30)
            val commentLength = bytes.u16(offset + 32)
            val externalAttributes = bytes.u32(offset + 38).toInt()
            val isSymbolicLink = versionMadeBy.ushr(8) == UNIX_HOST &&
                ((externalAttributes.ushr(16) and FILE_TYPE_MASK) == SYMBOLIC_LINK_FILE_TYPE)
            require(!isSymbolicLink) { "ZIP contains a symbolic link." }
            offset += 46 + fileNameLength + extraLength + commentLength
            require(offset <= endOffset) { "ZIP central directory is malformed." }
        }
    }

    private fun findEndOfCentralDirectory(bytes: ByteArray): Int? {
        val earliest = (bytes.size - 65_557).coerceAtLeast(0)
        for (index in bytes.size - 22 downTo earliest) {
            if (bytes.u32(index) == END_OF_CENTRAL_DIRECTORY_SIGNATURE.toLong()) return index
        }
        return null
    }

    private fun InputStream.readAtMost(maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "ZIP exceeds the compressed size limit." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun ByteArray.u16(offset: Int): Int {
        require(offset >= 0 && offset + 2 <= size) { "ZIP is malformed." }
        return (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun ByteArray.u32(offset: Int): Long {
        require(offset >= 0 && offset + 4 <= size) { "ZIP is malformed." }
        return (this[offset].toLong() and 0xff) or
            ((this[offset + 1].toLong() and 0xff) shl 8) or
            ((this[offset + 2].toLong() and 0xff) shl 16) or
            ((this[offset + 3].toLong() and 0xff) shl 24)
    }
}

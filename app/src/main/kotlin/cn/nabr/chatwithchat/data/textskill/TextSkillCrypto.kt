package cn.nabr.chatwithchat.data.textskill

import java.security.MessageDigest

internal fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(value)
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

internal fun sha256(value: String): String = sha256(value.toByteArray(Charsets.UTF_8))

internal fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size

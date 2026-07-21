package com.example.newproject

import java.security.MessageDigest

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

internal fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean =
    MessageDigest.isEqual(left, right)

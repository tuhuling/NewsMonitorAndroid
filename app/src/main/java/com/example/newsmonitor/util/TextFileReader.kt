package com.example.newsmonitor.util

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 读取用户选择的文本文件，自动识别 UTF-8 / UTF-16 BOM / GB18030。
 * 超过 maxBytes 时返回 null，避免超大文件占用内存。
 */
object TextFileReader {

    const val DEFAULT_MAX_BYTES = 5_000_000

    fun readTextAuto(
        context: Context,
        uri: Uri,
        maxBytes: Int = DEFAULT_MAX_BYTES
    ): String? {
        val bytes = readBytes(context, uri, maxBytes) ?: return null
        if (bytes.isEmpty()) return ""

        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return Charset.forName("UTF-16LE")
                .decode(ByteBuffer.wrap(bytes, 2, bytes.size - 2)).toString()
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return Charset.forName("UTF-16BE")
                .decode(ByteBuffer.wrap(bytes, 2, bytes.size - 2)).toString()
        }

        var offset = 0
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            offset = 3
        }

        val slice = bytes.copyOfRange(offset, bytes.size)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(slice))
                .toString()
        } catch (_: Exception) {
            try {
                Charset.forName("GB18030").decode(ByteBuffer.wrap(slice)).toString()
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun readBytes(context: Context, uri: Uri, maxBytes: Int): ByteArray? {
        val input = try {
            context.contentResolver.openInputStream(uri)
        } catch (_: Exception) {
            null
        } ?: return null

        return input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = try {
                    stream.read(buffer)
                } catch (_: Exception) {
                    return null
                }
                if (read < 0) break
                total += read
                if (total > maxBytes) return null
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }
}

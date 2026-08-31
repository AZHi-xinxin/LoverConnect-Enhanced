package com.lover.connect

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

internal object LocalHttpWire {
    fun readAsciiLine(input: InputStream, maxBytes: Int): String? {
        require(maxBytes > 0)
        val bytes = ByteArrayOutputStream()
        while (bytes.size() <= maxBytes) {
            val value = input.read()
            if (value < 0) {
                return if (bytes.size() == 0) null else bytes.toString(Charsets.ISO_8859_1.name())
            }
            if (value == '\n'.code) {
                return bytes.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
            }
            bytes.write(value)
        }
        throw IllegalArgumentException("HTTP line too long")
    }

    fun readExactBody(input: InputStream, contentLength: Int, maxBytes: Int): ByteArray {
        require(contentLength in 0..maxBytes)
        val body = ByteArray(contentLength)
        var total = 0
        while (total < contentLength) {
            val read = input.read(body, total, contentLength - total)
            if (read < 0) throw EOFException("HTTP body ended before Content-Length")
            total += read
        }
        return body
    }
}

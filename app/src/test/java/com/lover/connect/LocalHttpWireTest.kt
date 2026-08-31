package com.lover.connect

import java.io.ByteArrayInputStream
import java.io.EOFException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalHttpWireTest {
    @Test
    fun readsUtf8BodyByBytesNotCharacters() {
        val json = "{\"message\":\"晚安呀🌙\"}"
        val bytes = json.toByteArray(Charsets.UTF_8)
        val read = LocalHttpWire.readExactBody(ByteArrayInputStream(bytes), bytes.size, 1_024)
        assertEquals(json, String(read, Charsets.UTF_8))
    }

    @Test
    fun rejectsTruncatedBody() {
        val bytes = "短".toByteArray(Charsets.UTF_8)
        assertThrows(EOFException::class.java) {
            LocalHttpWire.readExactBody(ByteArrayInputStream(bytes), bytes.size + 1, 1_024)
        }
    }

    @Test
    fun readsCrLfHeaderLinesWithoutConsumingBody() {
        val wire = "POST /mcp/token HTTP/1.1\r\nContent-Length: 3\r\n\r\nabc"
            .toByteArray(Charsets.ISO_8859_1)
        val input = ByteArrayInputStream(wire)
        assertEquals("POST /mcp/token HTTP/1.1", LocalHttpWire.readAsciiLine(input, 1_024))
        assertEquals("Content-Length: 3", LocalHttpWire.readAsciiLine(input, 1_024))
        assertEquals("", LocalHttpWire.readAsciiLine(input, 1_024))
        assertEquals("abc", String(LocalHttpWire.readExactBody(input, 3, 1_024)))
    }
}

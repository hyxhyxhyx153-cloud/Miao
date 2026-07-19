package com.hyx.miao.data.remote

import com.hyx.miao.data.remote.dto.SseEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SseParserTest {

    @Test
    fun parse_stopsAtProtocolTerminatorBeforeBrokenTransportEof() = runBlocking {
        val response = failingResponseBody(
            """
            data: {"type":"delta","content":"你好"}

            data: {"type":"done","emotion":"happy","action":null,"emoji_id":null,"usage":{"input_tokens":1,"output_tokens":2}}

            data: [DONE]

            """.trimIndent()
        )

        val events = SseParser.parse(response.body).toList()

        assertEquals(2, events.size)
        assertEquals("你好", (events[0] as SseEvent.Delta).content)
        assertTrue(events[1] is SseEvent.Done)
        assertTrue(response.source.closed)
    }

    @Test
    fun parse_acceptsBrokenTransportEofAfterDoneEvent() = runBlocking {
        val response = failingResponseBody(
            """
            data: {"type":"delta","content":"完成"}

            data: {"type":"done","emotion":null,"action":null,"emoji_id":null,"usage":{"input_tokens":0,"output_tokens":0}}

            """.trimIndent()
        )

        val events = SseParser.parse(response.body).toList()

        assertEquals(listOf("完成"), events.filterIsInstance<SseEvent.Delta>().map { it.content })
        assertTrue(events.last() is SseEvent.Done)
        assertTrue(response.source.closed)
    }

    @Test
    fun parse_propagatesBrokenTransportEofBeforeDoneEvent() {
        val response = failingResponseBody(
            """
            data: {"type":"delta","content":"未完成"}

            """.trimIndent()
        )

        assertThrows(IOException::class.java) {
            runBlocking { SseParser.parse(response.body).toList() }
        }
        assertTrue(response.source.closed)
    }

    @Test
    fun parse_rejectsCleanEofBeforeTerminalEvent() {
        val response = """
            data: {"type":"delta","content":"未完成"}

        """.trimIndent().toResponseBody("text/event-stream; charset=utf-8".toMediaType())

        assertThrows(IOException::class.java) {
            runBlocking { SseParser.parse(response).toList() }
        }
    }

    private fun failingResponseBody(content: String): TrackingResponse {
        val source = FailingAfterContentSource("$content\n\n".toByteArray(Charsets.UTF_8))
        val bufferedSource = source.buffer()
        val body = object : ResponseBody() {
            override fun contentType() = "text/event-stream; charset=utf-8".toMediaType()
            override fun contentLength() = -1L
            override fun source(): BufferedSource = bufferedSource
        }
        return TrackingResponse(body, source)
    }

    private data class TrackingResponse(
        val body: ResponseBody,
        val source: FailingAfterContentSource,
    )

    private class FailingAfterContentSource(
        private val content: ByteArray,
    ) : Source {
        var closed: Boolean = false
            private set
        private var offset = 0

        override fun read(sink: Buffer, byteCount: Long): Long {
            if (closed) throw IOException("source is closed")
            if (offset >= content.size) throw IOException("unexpected end of stream")
            val count = minOf(byteCount, (content.size - offset).toLong()).toInt()
            sink.write(content, offset, count)
            offset += count
            return count.toLong()
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() {
            closed = true
        }
    }
}

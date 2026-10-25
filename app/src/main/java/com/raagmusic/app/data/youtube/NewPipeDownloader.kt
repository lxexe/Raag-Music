package com.raagmusic.app.data.youtube

import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * NewPipe's API is synchronous. An interruptible wait around OkHttp's asynchronous call
 * lets runInterruptible cancel the actual socket rather than merely discard a stale result.
 */
class NewPipeDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build(),
) : Downloader() {
    override fun execute(request: Request): Response {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Extraction cancelled")
        val started = System.nanoTime()
        val builder = okhttp3.Request.Builder().url(request.url()).header("User-Agent", USER_AGENT)
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }
        val body = request.dataToSend()?.toRequestBody()
            ?: if (request.httpMethod() in setOf("POST", "PUT", "PATCH")) byteArrayOf().toRequestBody() else null
        val call = client.newCall(builder.method(request.httpMethod(), body).build())
        val deadlineNanos = deadline.get()
        deadlineNanos?.let { end ->
            val remaining = end - System.nanoTime()
            if (remaining <= 0) throw java.net.SocketTimeoutException("Audio client deadline exceeded")
            call.timeout().timeout(remaining, TimeUnit.NANOSECONDS)
        }
        val result = AtomicReference<Result<Response>>()
        val completed = CountDownLatch(1)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                result.set(Result.failure(e))
                completed.countDown()
            }
            override fun onResponse(call: Call, response: okhttp3.Response) {
                try {
                    result.set(runCatching {
                        response.use {
                            if (it.code == 429) throw YoutubeRateLimitedException(
                                (it.header("Retry-After")?.toLongOrNull() ?: 60).coerceIn(30, 300) * 1000)
                            if (it.request.url.host == "consent.youtube.com" ||
                                it.request.url.host == "www.google.com" && it.request.url.encodedPath.startsWith("/sorry")) {
                                throw ReCaptchaException("YouTube requires verification. Try again later.", request.url())
                            }
                            Response(it.code, it.message, it.headers.toMultimap(), it.body?.string().orEmpty(), it.request.url.toString())
                        }
                    })
                } finally {
                    completed.countDown()
                }
            }
        })
        try {
            // Cancellation cannot interrupt every platform DNS lookup. Bound our synchronous
            // wait too, so a stalled DNS worker cannot hold the audio resolver lane indefinitely.
            if (deadlineNanos == null) completed.await()
            else if (!completed.await((deadlineNanos - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)) {
                call.cancel()
                throw java.net.SocketTimeoutException("Audio client deadline exceeded")
            }
            val response = requireNotNull(result.get()).getOrThrow()
            com.raagmusic.app.playback.PlaybackTrace.network("http:" + call.request().url.encodedPath.substringAfterLast('/'),
                (System.nanoTime() - started) / 1_000_000)
            return response
        } catch (e: InterruptedException) {
            call.cancel()
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Extraction cancelled").apply { initCause(e) }
        }
    }

    companion object {
        private val deadline = ThreadLocal<Long>()
        internal fun <T> withDeadline(millis: Long, block: () -> T): T {
            deadline.set(System.nanoTime() + millis * 1_000_000)
            try { return block() } finally { deadline.remove() }
        }
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
    }
}

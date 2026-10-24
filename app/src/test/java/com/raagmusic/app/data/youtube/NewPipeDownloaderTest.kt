package com.raagmusic.app.data.youtube

import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NewPipeDownloaderTest {
    @Test fun audioDeadlineReleasesResolverEvenWhilePlatformDnsIsStillBlocked() {
        val dnsStarted = CountDownLatch(1)
        val releaseDns = CountDownLatch(1)
        val client = OkHttpClient.Builder().dns(object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                dnsStarted.countDown()
                releaseDns.await(5, TimeUnit.SECONDS)
                throw UnknownHostException("Blocked fixture DNS")
            }
        }).build()
        val downloader = NewPipeDownloader(client)
        try {
            val start = System.nanoTime()
            assertThrows(SocketTimeoutException::class.java) {
                NewPipeDownloader.withDeadline(300) { downloader.get("https://playback-test.invalid/player") }
            }
            assertEquals("Request reached the deliberately blocked DNS", 0L, dnsStarted.count)
            assertEquals("DNS is still blocked when the resolver returns", 1L, releaseDns.count)
            assertTrue("Do not await the five-second DNS callback", (System.nanoTime() - start) / 1_000_000 < 2_000)
        } finally {
            releaseDns.countDown()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}

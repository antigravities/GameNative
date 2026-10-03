package app.gamenative.utils

import okhttp3.Dns
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.dnsoverhttps.DnsOverHttps
import timber.log.Timber
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

object Net {

    private val bootstrapClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .build()

    private val doh: DnsOverHttps = DnsOverHttps.Builder()
        .client(bootstrapClient)
        .url("https://dns.google/dns-query".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByName("8.8.8.8"),
            InetAddress.getByName("8.8.4.4"),
        )
        .build()

    val fallbackDns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return try {
                doh.lookup(hostname)
            } catch (e: Exception) {
                Timber.w(e, "DoH lookup failed for $hostname, falling back to system DNS")
                Dns.SYSTEM.lookup(hostname)
            }
        }
    }

    // Hosts this fork never contacts. downloads.gamenative.app (driver/component CDN) is intentionally
    // NOT listed because the emulator needs it.
    private val blockedHosts = setOf("api.gamenative.app", "relay.gamenative.app")

    // An OkHttp Interceptor sees every request before it hits the network; throwing IOException makes
    // callers treat it as an ordinary network failure (e.g. ApiResult.NetworkError) instead of crashing.
    private val blockGameNativeBackend = Interceptor { chain ->
        val host = chain.request().url.host
        if (host in blockedHosts) throw IOException("Blocked request to $host (disabled in this fork)")
        chain.proceed(chain.request())
    }

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(blockGameNativeBackend)
            .dns(fallbackDns)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.MINUTES)
            .pingInterval(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun httpForParallelDownloads(parallelDownloads: Int): OkHttpClient {
        val hostConcurrency = parallelDownloads.coerceIn(4, 32)
        val dispatcher = Dispatcher().apply {
            maxRequestsPerHost = hostConcurrency
            maxRequests = maxOf(64, hostConcurrency * 2)
        }
        return http.newBuilder()
            .dispatcher(dispatcher)
            .readTimeout(5, TimeUnit.MINUTES)
            // Hard cap on the whole call. callTimeout is the only OkHttp setting that bounds the
            // entire request and actively interrupts a stalled socket — `execute()` is a blocking
            // call, so the kotlinx `withTimeout` wrapped around it can't cancel it (a thread inside
            // a blocking JVM call isn't at a cancellable suspension point). Without this a
            // half-open/trickling connection could keep a cloud-save download alive indefinitely
            // and hang the pre-launch syncing dialog.
            .callTimeout(2, TimeUnit.MINUTES)
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
    }
}

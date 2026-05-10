package app.gamenative.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.TimeUnit

object SteamPeekService {
    private const val BASE = "https://steampeek.hu/"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"

    private val http = OkHttpClient.Builder().apply {
        protocols(listOf(Protocol.HTTP_1_1))
        connectTimeout(15, TimeUnit.SECONDS)
        readTimeout(30, TimeUnit.SECONDS)
    }.build()

    private val cacheMutex = Mutex()
    private val cache = mutableMapOf<Int, List<Int>>()

    public suspend fun fetch(appid: Int): List<Int> = withContext(Dispatchers.IO){
        cacheMutex.withLock {
            cache[appid]?.let { return@withLock it }

            val result = try {
                val req = Request.Builder().apply {
                    url("$BASE/?appid=$appid")
                    header("User-Agent", USER_AGENT)
                    header("Accept", "text/html")
                    header("Origin", BASE)
                }.build()

                http.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) {
                        Timber.tag("SteamPeek").w("Couldn't fetch app page for $appid")
                        return@withLock emptyList()
                    }

                    val html = response.body?.string() ?: return@withContext emptyList()

                    val matches = Regex("""article class="lister.*" data-appid="(\d+)"""").findAll(html)

                    val ids = matches.map { it.groupValues[1].toInt() }.toList()

                    if (ids.isEmpty()) {
                        Timber.tag("SteamPeek").i("Could not find any matching apps for app $appid")
                        return@withLock emptyList()
                    }

                    ids.filter { it != appid }
                }
            } catch (e: Exception) {
                Timber.tag("SteamPeek").e(e, "Couldn't fetch recommendations for $appid")
                emptyList()
            }

            cache[appid] = result
            result
        }
    }
}

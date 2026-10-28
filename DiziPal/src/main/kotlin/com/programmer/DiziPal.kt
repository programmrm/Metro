// ! Bu araç @programmer tarafından.

package com.programmer

import android.util.Log
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class DiziPal : MainAPI() {
    override var mainUrl              = "https://dizipal1592.com"
    override var name                 = "DiziPal"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override var supportedTypes       = setOf(TvType.TvSeries, TvType.Movie)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay       = 150L
    override var sequentialMainPageScrollDelay = 150L

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller): Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val body = runCatching { response.peekBody(1024 * 1024).string() }.getOrDefault("")

            if (body.contains("Just a moment")) {
                return cloudflareKiller.intercept(chain)
            }

            return response
        }
    }

    private val mapper by lazy {
        ObjectMapper().registerModule(KotlinModule.Builder().build()).apply {
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }
    }

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1",
    )

    private val homeSections = setOf("Yeni Diziler", "Filmler")

    override val mainPage = mainPageOf(
        "${mainUrl}/yabanci-dizi-izle"              to "Yeni Diziler",
        "${mainUrl}/yeni-eklenen-dizi-bolumler"     to "Son Bölümler",
        "${mainUrl}/film-izle"                      to "Filmler",
        "${mainUrl}/kanal/netflix"                  to "Netflix",
        "${mainUrl}/kanal/exxen"                    to "Exxen",
        "${mainUrl}/kanal/max"                      to "Max",
        "${mainUrl}/kanal/disney"                   to "Disney+",
        "${mainUrl}/kanal/amazon"                   to "Amazon Prime",
        "${mainUrl}/kanal/tod"                      to "TOD (beIN)",
        "${mainUrl}/kanal/tabii"                    to "Tabii",
    )

    private suspend fun fetch(url: String): Document? = try {
        app.get(url, interceptor = interceptor, headers = commonHeaders).document
    } catch (e: Exception) {
        Log.e("DZP", "fetch error ($url): ${e.message}")
        null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val name = request.name
        try {
            if (page > 1) return newHomePageResponse(name, emptyList(), false)
            val doc = fetch(request.data) ?: return newHomePageResponse(name, emptyList(), false)
            val items = if (name in homeSections) parseCards(doc) else parseEpisodeCards(doc)
                .ifEmpty { parseCards(doc) }
            Log.d("DZP", "$name: ${items.size} öğe")
            return newHomePageResponse(name, items, false)
        } catch (e: Exception) {
            Log.e("DZP", "getMainPage error: ${e.message}")
            return newHomePageResponse(name, emptyList(), false)
        }
    }

    /** a.tc kartları: /series/<slug> ya da /movies/<slug> */
    private fun parseCards(doc: Document): List<SearchResponse> {
        val seen = mutableSetOf<String>()
        val items = mutableListOf<SearchResponse>()

        for (card in doc.select("a.tc[href]")) {
            val path = card.attr("href")
            val isMovie = path.startsWith("/movies/")
            val isSeries = path.startsWith("/series/")
            if (!isMovie && !isSeries) continue
            val url = fixUrlNull(path) ?: continue
            if (!seen.add(url)) continue

            val title = card.selectFirst(".tt")?.text()?.trim()
                ?: card.attr("aria-label").substringBefore(",").trim()
            if (title.isEmpty()) continue

            val label = card.attr("aria-label")
            val year = Regex("""\b(19\d{2}|20\d{2})\b""").find(label)?.value?.toIntOrNull()
            val poster = fixUrlNull(card.selectFirst("img")?.attr("src"))

            items.add(
                if (isMovie) {
                    newMovieSearchResponse(title, url, TvType.Movie) {
                        this.posterUrl = poster
                        this.year = year
                    }
                } else {
                    newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                        this.posterUrl = poster
                        this.year = year
                    }
                }
            )
        }
        return items
    }

    /** a.ecl kartları: /bolum/<slug>-<s>x<e> → dizi sayfasına yönlendirilir */
    private fun parseEpisodeCards(doc: Document): List<SearchResponse> {
        val seen = mutableSetOf<String>()
        val items = mutableListOf<SearchResponse>()

        for (card in doc.select("a.ecl[href]")) {
            val path = card.attr("href")
            if (!path.startsWith("/bolum/")) continue
            val label = card.attr("aria-label")
            // "Dizi Adı, 4. Sezon 1. Bölüm, dublaj" -> "Dizi Adı"
            val title = label.replace(EP_CARD_SUFFIX_REGEX, "").trim().ifEmpty {
                card.selectFirst(".ett, .ttl, .tt")?.text()?.trim().orEmpty()
            }
            if (title.isEmpty()) continue

            val seriesPath = SERIES_PATH_REGEX.find(path)?.value ?: continue
            val url = fixUrlNull(seriesPath) ?: continue
            if (!seen.add(url)) continue

            val year = Regex("""\b(19\d{2}|20\d{2})\b""").find(label)?.value?.toIntOrNull()
            items.add(
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    this.posterUrl = fixUrlNull(card.selectFirst("img")?.attr("src"))
                    this.year = year
                }
            )
        }
        return items
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val url = "$mainUrl/arama-yap?s=" + URLEncoder.encode(query.trim(), "UTF-8")
            val doc = fetch(url) ?: return emptyList()
            parseCards(doc)
        } catch (e: Exception) {
            Log.e("DZP", "search error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val doc = fetch(url) ?: return null
            parseByJsonLd(doc, url) ?: parseByDom(doc, url)
        } catch (e: Exception) {
            Log.e("DZP", "load error: ${e.message}")
            null
        }
    }

    private fun normalizeEpisodeUrl(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val absolute = value.replace("http://$mainUrl/", "$mainUrl/")
            .replace("https://$mainUrl/", "$mainUrl/")
        return if (absolute.startsWith("http")) absolute else "$mainUrl/${absolute.trimStart('/')}"
    }

    private suspend fun parseByJsonLd(doc: Document, url: String): LoadResponse? {
            var title: String? = null
            var poster: String? = null
            var plot: String? = null
            var year: Int? = null
            var score: Double? = null
            var isMovie = false
            val episodes = LinkedHashMap<String, Episode>()

        for (script in doc.select("script[type=\"application/ld+json\"]")) {
            val node = runCatching { mapper.readTree(script.data()) }.getOrNull() ?: continue
            when (node.path("@type").asText()) {
                "Movie" -> isMovie = true
                "TVSeries" -> isMovie = false
                else -> continue
            }

            if (title == null) title = node.path("name").asText(null)?.ifBlank { null }
            if (poster == null) poster = node.path("image").asText(null)?.ifBlank { null }
            if (plot == null) plot = node.path("description").asText(null)?.ifBlank { null }
            if (year == null) year = node.path("datePublished").asText(null)?.take(4)?.toIntOrNull()
            if (score == null) {
                score = node.path("aggregateRating").path("ratingValue").asDouble(0.0)
                    .takeIf { it > 0.0 }
            }

            if (isMovie) continue
            val seasonsNode = node.path("containsSeason")
            val seasons = when {
                seasonsNode.isArray -> seasonsNode
                seasonsNode.isObject -> mapper.createArrayNode().add(seasonsNode)
                else -> continue
            }
            for ((index, season) in seasons.withIndex()) {
                val seasonNo = season.path("seasonNumber").asInt(0).takeIf { it > 0 }
                    ?: SEASON_URL_REGEX.find(season.path("url").asText(""))?.groupValues?.get(1)?.toIntOrNull()
                    ?: (index + 1)
                val epsNode = season.path("episode")
                val eps = when {
                    epsNode.isArray -> epsNode
                    epsNode.isObject -> mapper.createArrayNode().add(epsNode)
                    else -> continue
                }
                for ((epIndex, ep) in eps.withIndex()) {
                    val epUrl = normalizeEpisodeUrl(ep.path("url").asText(null)) ?: continue
                    val epNo = ep.path("episodeNumber").asInt(0).takeIf { it > 0 } ?: (epIndex + 1)
                    episodes[epUrl] = newEpisode(epUrl) {
                        this.name = ep.path("name").asText(null)
                        this.season = seasonNo
                        this.episode = epNo
                    }
                }
            }
        }

        if (title.isNullOrBlank()) return null

        val domTitle = doc.selectFirst("h1")?.text()?.trim()?.ifEmpty { null }

        return if (isMovie || episodes.isEmpty() && url.contains("/movies/")) {
            newMovieLoadResponse(domTitle ?: title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.score = score?.let { Score.from10(it) }
            }
        } else {
            if (episodes.isEmpty()) return null
            newTvSeriesLoadResponse(domTitle ?: title, url, TvType.TvSeries, episodes.values.toList()) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.score = score?.let { Score.from10(it) }
            }
        }
    }

    private suspend fun parseByDom(doc: Document, url: String): LoadResponse? {
        val title = doc.selectFirst("h1")?.text()?.trim()?.ifEmpty { null }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.ifEmpty { null }
            ?: return null
        val poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()?.ifEmpty { null }
            ?: doc.selectFirst("p:matches(^.{40,})")?.text()?.trim()

        return if (url.contains("/movies/")) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, emptyList()) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DZP", "loadLinks: $data")
        val html = try {
            // React flight payload'ındaki JSON çift kaçışlı gelir ("\"ciphertext\":...");
            // regexlerimiz düz tırnak beklediği için normalize ediyoruz.
            app.get(data, interceptor = interceptor, headers = commonHeaders).text
                .replace("\\\"", "\"")
        } catch (e: Exception) {
            Log.e("DZP", "bolum sayfasi alinamadi: ${e.message}")
            return false
        }

        var created = false
        val track: (ExtractorLink) -> Unit = { link -> created = true; callback(link) }

        // 1) Sayfadaki aktif player'ın şifreli embed URL'si
        val seenEmbeds = mutableSetOf<String>()
        val activeEmbed = decryptDizipalData(html).ifEmpty { null }
        if (activeEmbed != null && seenEmbeds.add(activeEmbed)) {
            Log.d("DZP", "aktif player embed: $activeEmbed")
            DizipalPlayer().getUrl(activeEmbed, data, subtitleCallback, track)
        }

        // 2) Sayfadaki diger player secenekleri (?player=<hash>)
        for (match in PLAYER_HREF_REGEX.findAll(html)) {
            val path = match.groupValues[1].replace("\\/", "/")
            if (!path.contains("player=")) continue
            val playerUrl = normalizeEpisodeUrl(path.substringBefore("?")) + "?" + path.substringAfter("?")
            val playerHtml = try {
                app.get(playerUrl, interceptor = interceptor, headers = commonHeaders).text
            } catch (e: Exception) {
                continue
            }
            val embed = decryptDizipalData(playerHtml).ifEmpty { null }
            if (embed == null || !seenEmbeds.add(embed)) continue
            Log.d("DZP", "alternatif player embed: $embed")
            DizipalPlayer().getUrl(embed, playerUrl, subtitleCallback, track)
        }

        Log.d("DZP", "loadLinks sonuc: $created")
        return created
    }

    private fun String.decodeHex(): ByteArray {
        check(length % 2 == 0) { "Hex string çift uzunlukta olmalıdır" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun decryptDizipalData(rawJsonText: String): String {
        return try {
            val passphrase = "3hPn4uCjTVtfYWcjIcoJQ4cL1WWk1qxXI39egLYOmNv6IblA7eKJz68uU3eLzux1biZLCms0quEjTYniGv5z1JcKbNIsDQFSeIZOBZJz4is6pD7UyWDggWWzTLBQbHcQFpBQdClnuQaMNUHtLHTpzCvZy33p6I7wFBvL4fnXBYH84aUIyWGTRvM2G5cfoNf4705tO2kv"

            val normalized = rawJsonText.replace("\\\"", "\"")

            val ctMatch = """"ciphertext"\s*:\s*"([^"]+)"""".toRegex().find(normalized)?.groupValues?.get(1)
                ?: return ""

            val ivMatch = """"iv"\s*:\s*"([^"]+)"""".toRegex().find(normalized)?.groupValues?.get(1)
                ?: return ""

            val saltMatch = """"salt"\s*:\s*"([^"]+)"""".toRegex().find(normalized)?.groupValues?.get(1)
                ?: return ""

            val salt = saltMatch.decodeHex()
            val iv = ivMatch.decodeHex()
            val ciphertext = android.util.Base64.decode(ctMatch, android.util.Base64.DEFAULT)

            val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
            val spec = javax.crypto.spec.PBEKeySpec(passphrase.toCharArray(), salt, 999, 256)
            val secretKey = factory.generateSecret(spec)
            val secret = javax.crypto.spec.SecretKeySpec(secretKey.encoded, "AES")

            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secret, javax.crypto.spec.IvParameterSpec(iv))

            val decryptedBytes = cipher.doFinal(ciphertext)
            var finalUrl = String(decryptedBytes, Charsets.UTF_8).replace("\\/", "/")

            Log.d("DZP", "AES çözümleme: $finalUrl")

            if (finalUrl.startsWith("://")) {
                finalUrl = "https$finalUrl"
            } else if (finalUrl.startsWith("//")) {
                finalUrl = "https:$finalUrl"
            } else if (!finalUrl.startsWith("http")) {
                finalUrl = "https://$finalUrl"
            }

            finalUrl
        } catch (e: Exception) {
            Log.e("DZP", "AES çözümleme hatası: ${e.message}")
            ""
        }
    }

    companion object {
        private val SERIES_PATH_REGEX = Regex("""/series/[a-z0-9-]+""")
        private val SEASON_URL_REGEX = Regex("""/sezon-(\d+)""")
        private val PLAYER_HREF_REGEX = Regex("""\"href\":\"([^\"]*\?player=[^\"]+)\"""")
        private val EP_CARD_SUFFIX_REGEX = Regex(""",\s*\d+\.\s*Sezon[\s\S]*$""")
    }
}

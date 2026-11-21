package com.programmer

import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class Cinehd : MainAPI() {
    override var mainUrl              = "https://cinehd.vc"
    override var name                 = "CineHD"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    private val mapper by lazy { jacksonObjectMapper() }
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller, mainUrl) }

    private val pageHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1"
    )

    private suspend fun fetchDocument(url: String, referer: String? = null): Document? = try {
        app.get(
            url,
            headers = pageHeaders,
            referer = referer,
            interceptor = interceptor,
            timeout = REQUEST_TIMEOUT_S
        ).document
    } catch (e: Exception) {
        Log.e(TAG, "fetch error ($url): ${e.message}")
        null
    }

    class CloudflareInterceptor(
        private val cloudflareKiller: CloudflareKiller,
        mainUrl: String
    ) : Interceptor {
        private val siteHost = runCatching { java.net.URI(mainUrl).host ?: "" }.getOrDefault("")

        @Volatile
        private var lastSolveAttemptMs = 0L

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()

            if (request.url.host != siteHost) return chain.proceed(request)

            val response = chain.proceed(withSavedCookies(request))
            if (!isChallenge(response)) return response

            if (System.currentTimeMillis() - lastSolveAttemptMs < SOLVE_INTERVAL_MS) return response
            lastSolveAttemptMs = System.currentTimeMillis()

            response.close()
            cloudflareKiller.savedCookies.remove(siteHost)

            if (solveChallenge(request.url.toString())) {
                val retried = chain.proceed(withSavedCookies(request))
                if (!isChallenge(retried)) return retried
                retried.close()
                cloudflareKiller.savedCookies.remove(siteHost)
            }

            return chain.proceed(request)
        }

        private fun withSavedCookies(request: Request): Request {
            if (cloudflareKiller.savedCookies[siteHost].isNullOrEmpty()) return request
            return runCatching {
                val builder = request.newBuilder()
                cloudflareKiller.getCookieHeaders(request.url.toString())
                    .toMultimap()
                    .forEach { (name, values) ->
                        val value = values.firstOrNull() ?: return@forEach
                        builder.header(name, value)
                    }
                builder.build()
            }.getOrDefault(request)
        }

        private fun isChallenge(response: Response): Boolean {
            if (response.code != 403 && response.code != 503) return false
            if (response.header("cf-mitigated").equals("challenge", true)) return true
            val body = runCatching { response.peekBody(CHALLENGE_PEEK_LIMIT).string() }.getOrDefault("")
            return body.contains("Just a moment", ignoreCase = true)
        }

        private fun solveChallenge(url: String): Boolean {
            if (adoptCookies(url)) return true
            return runCatching {
                runBlocking {
                    WebViewResolver(
                        Regex(".^"),
                        listOf(Regex(".")),
                        null,
                        false,
                        null,
                        null,
                        CHALLENGE_TIMEOUT_MS
                    ).resolveUsingWebView(url, null, "GET") { adoptCookies(url) }
                }
                adoptCookies(url)
            }.getOrDefault(false)
        }

        private fun adoptCookies(url: String): Boolean {
            val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull() ?: return false
            if (!cookie.contains("cf_clearance")) return false
            val parsed = parseCookies(cookie)
            synchronized(cloudflareKiller.savedCookies) {
                cloudflareKiller.savedCookies[siteHost] = parsed
            }
            return true
        }

        private fun parseCookies(raw: String): Map<String, String> = raw.split(";")
            .mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) null
                else part.substring(0, idx).trim() to part.substring(idx + 1).trim()
            }
            .filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
            .toMap()
        }

    override val mainPage = mainPageOf(
        "${mainUrl}/category/film-izle-2/"               to "Filmler",
        "${mainUrl}/tur/bilim-kurgu-filmlerini-izleyin-5/" to "Bilim Kurgu",
        "${mainUrl}/yabancidiziizle-5/"                  to "Diziler",
        "${mainUrl}/film-robotu-1/"                      to "Keşfet",
        "${mainUrl}/dil/turkce-dublajli-film-izleyin-5/" to "Türkçe Dublaj",
        "${mainUrl}/yil/2026-filmleri-izle/"             to "2026 Yapımları",
        "${mainUrl}/yil/2025-filmleri-izle-3/"           to "2025 Yapımları"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}?page=$page" else request.data
        val document = fetchDocument(url) ?: return newHomePageResponse(request.name, emptyList(), false)
        val items = document.select("a.poster[data-token], div.poster[data-token]")
            .mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        val title = this.attr("title").ifEmpty {
            this.selectFirst(".poster-title")?.text()?.trim()
        } ?: return null

        val img = this.selectFirst(".poster-wrapper img, img")
        val posterUrl = fixUrlNull(
            img?.attr("data-src").takeIf { it?.isNotBlank() == true }
                ?: img?.attr("src").takeIf { it?.isNotBlank() == true && !it.contains("data:image") }
        )

        val info = this.selectFirst(".poster-info, .poster-meta, .poster-content")
        val year = info?.selectFirst("span.year")?.text()?.trim()?.toIntOrNull()
            ?: info?.selectFirst(".poster-meta span")?.text()?.trim()?.toIntOrNull()
        val rating = info?.selectFirst(".imdb")?.text()?.trim()?.toFloatOrNull()

        val isSeries = href.contains("/dizi/")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.year = year
                this.score = rating?.let { Score.from10(it) }
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.year = year
                this.score = rating?.let { Score.from10(it) }
            }
        }
    }

    private fun Element.toSearchResultResponse(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        val title = this.selectFirst(".title, h4, .search-result-title")?.text()?.trim() ?: return null

        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(
            img?.attr("data-src").takeIf { it?.isNotBlank() == true }
                ?: img?.attr("src").takeIf { it?.isNotBlank() == true }
        )

        val year = this.selectFirst(".year")?.text()?.trim()?.toIntOrNull()
        val rating = this.selectFirst(".imdb")?.text()?.trim()?.toFloatOrNull()
        val type = this.selectFirst(".type")?.text()?.trim()
        val isSeries = href.contains("/dizi/") || type?.contains("Dizi", ignoreCase = true) == true

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.year = year
                this.score = rating?.let { Score.from10(it) }
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.year = year
                this.score = rating?.let { Score.from10(it) }
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val text = try {
            app.get(
                "${mainUrl}/search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8.name()),
                headers = mapOf("X-Requested-With" to "fetch", "Content-Type" to "application/json"),
                interceptor = interceptor,
                timeout = REQUEST_TIMEOUT_S
            ).text
        } catch (e: Exception) {
            Log.e(TAG, "search error: ${e.message}")
            return emptyList()
        }
        return runCatching {
            val root = mapper.readTree(text)
            val results = root.get("results")
            if (results?.isArray != true) return@runCatching emptyList()
            (0 until results.size()).mapNotNull { i ->
                val html = results[i].asText()
                Jsoup.parse(html).selectFirst("a.search-result")?.toSearchResultResponse()
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = fetchDocument(url) ?: return null
        val jsonLd = document.selectFirst("script[type='application/ld+json']")
        val root = runCatching { jsonLd?.data()?.let { mapper.readTree(it) } }.getOrNull()

        val title = root?.get("name")?.asText(null)
            ?: document.selectFirst("h1.section-title, h1[class*='title']")?.text()
                ?.replace(Regex("""\s+<small>.*""", RegexOption.DOT_MATCHES_ALL), "")
                ?.trim()
            ?: document.selectFirst("title")?.text()?.trim()
                ?.replace(" izle | CineHD | Film izle | HD Film izle", "")
                ?.replace(" | CineHD", "")?.trim()
            ?: return null

        val poster = root?.get("image")?.takeIf { it.isTextual }?.asText().let { it?.takeIf { v -> v.isNotBlank() } }
            ?: fixUrlNull(document.selectFirst("img[data-found='1'][src*='/images/list/cover/']")?.attr("src"))
            ?: document.selectFirst("img[data-found='1'][src*='/images/list/poster/']")?.attr("src")

        val description = root?.get("description")?.asText(null)
            ?: document.selectFirst("p:matches(^.{50,})")?.text()?.trim()

        val datePublished = root?.get("datePublished")?.asText("")
        val year = datePublished?.takeIf { it.length >= 4 }?.take(4)?.toIntOrNull()
            ?: document.selectFirst("a[href*='/yil/']")?.text()?.trim()?.toIntOrNull()

        val tags = root?.get("genre")?.takeIf { it.isArray }?.mapNotNull { it.asText(null) }
            ?: document.select("a[href*='/tur/'], [class*='genre'] a, [class*='tur'] a")
                .mapNotNull { it.text().trim().takeIf { t -> t.isNotBlank() } }

        val rating = root?.get("aggregateRating")?.get("ratingValue")?.asText("")?.toFloatOrNull()
            ?: document.selectFirst("[class*='imdb'] span, [class*='imdb']")?.text()?.trim()
                ?.replace(Regex("""[^\d.,]"""), "")?.split("/", " ")[0]?.toFloatOrNull()

        val duration = root?.get("duration")?.asText("")?.let { parseIsoDuration(it) }

        val castNames = root?.get("actor")?.takeIf { it.isArray }
            ?.mapNotNull { it?.get("name")?.asText(null) }
            ?: document.select("a[href*='/oyuncu/']").mapNotNull { it.text().trim().takeIf { it.isNotBlank() } }
        val actors = castNames?.map { Actor(it) } ?: emptyList()

        val trailer = document.selectFirst("iframe[src*='youtube']")?.attr("src")
            ?: document.selectFirst("a[href*='youtube']")?.attr("href")

        val jsonType = root?.get("@type")?.asText("") ?: ""
        val isSeries = url.contains("/dizi/") || jsonType == "TVSeries"

        val episodes = mutableListOf<Episode>()
        if (isSeries) {
            root?.get("containsSeason")?.takeIf { it.isArray }?.let { seasons ->
                for (i in 0 until seasons.size()) {
                    val season = seasons[i]
                    val seasonNumber = season.get("seasonNumber")?.asInt(1)
                    val eps = season.get("episode")
                    if (eps?.isArray == true) {
                        for (j in 0 until eps.size()) {
                            val ep = eps[j]
                            val episodeNumber = ep.get("episodeNumber")?.asInt(-1)
                            val epUrl = ep.get("url")?.asText("") ?: continue
                            if (epUrl.isBlank()) continue
                            val epName = ep.get("name")?.asText("") ?: ""
                            episodes.add(newEpisode(fixUrl(epUrl)) {
                                this.season = seasonNumber
                                this.episode = episodeNumber
                                this.name = epName
                            })
                        }
                    }
                }
            }
        }

        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags.orEmpty()
                this.score = rating?.let { Score.from10(it) }
                this.duration = duration
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags.orEmpty()
                this.score = rating?.let { Score.from10(it) }
                this.duration = duration
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    private fun parseIsoDuration(text: String): Int? {
        val hours = Regex("""(\d+)H""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val mins = Regex("""(\d+)M""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return if (hours == 0 && mins == 0) null else hours * 3600 + mins * 60
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = fetchDocument(data) ?: return false
        val iframeEl = document.selectFirst(
            "iframe[data-src*='/video/embed/'], iframe[src*='/video/embed/'], " +
                "iframe.close[data-src], iframe.close[src], iframe[data-src], iframe[src]"
        ) ?: return false
        val embedUrl = fixUrl(iframeEl.attr("data-src").ifBlank { iframeEl.attr("src") })
        return resolveEmbed(embedUrl, data, subtitleCallback, callback)
    }

    private suspend fun resolveEmbed(
        embedUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val page = try {
            app.get(embedUrl, referer = referer, interceptor = interceptor, timeout = REQUEST_TIMEOUT_S).text
        } catch (e: Exception) {
            Log.e(TAG, "embed error ($embedUrl): ${e.message}")
            return false
        }

        parseEmbedSubtitles(page, embedUrl, subtitleCallback)

        val embedHost = runCatching { java.net.URI(embedUrl).host }.getOrNull() ?: ""
        val videoUrl = decodeSourcesUrl(page, embedHost)
            ?: runLegacyAssignDecode(page)

        if (videoUrl == null || !videoUrl.startsWith("http")) {
            return runCatching { loadExtractor(embedUrl, referer, {}, callback) }.getOrDefault(false)
        }

        parseM3u8Subtitles(videoUrl, embedUrl, subtitleCallback)

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = videoUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = embedUrl
                this.quality = -1
                headers = mapOf(
                    "Referer" to embedUrl,
                    "Origin" to mainUrl,
                )
            }
        )
        return true
    }

    private fun runLegacyAssignDecode(page: String): String? {
        val decoded = decodePackers(page)
        for (assign in ASSIGN_REGEX.findAll(decoded)) {
            val fn = assign.groupValues[2]
            val chunkStr = assign.groupValues[3]
            val chunks = Regex("\"([^\"]+)\"").findAll(chunkStr).map { it.groupValues[1] }.toList()
                .map { it.replace("\\/", "/") }
            if (chunks.isEmpty()) continue
            val fnBody = extractFunctionBody(decoded, fn) ?: continue
            val videoUrl = runEmbedDecode(chunks, fnBody) ?: continue
            if (videoUrl.startsWith("http")) return videoUrl
        }
        return null
    }

    private fun decodeSourcesUrl(html: String, hostname: String = ""): String? {
        val fileVar = Regex("""sources:\s*\[\{\s*file:\s*(\w+)""").find(html)?.groupValues?.get(1)
            ?: return null
        val call = Regex("""var\s+${Regex.escape(fileVar)}\s*=\s*(\w+)\s*\(""").find(html)
            ?: return null
        val fnName = call.groupValues[1]
        val rest = html.substring(call.range.last + 1)
        val arg = Regex("""^"([^"]*)"\s*\.split\(\s*['"]([^'"])['"]\s*\)""").find(rest)
            ?: return null
        val parts = arg.groupValues[1].split(arg.groupValues[2])
        val body = extractFunctionBody(html, fnName) ?: return null
        return runCatching { decodeStage2(parts, body, hostname) }.getOrNull()
    }

    private fun decodeStage2(parts0: List<String>, body: String, hostname: String = ""): String? {
        val parts = parts0.toMutableList()
        val n = parts.size - 2
        if (n < 0) return null
        val de9 = 8 + (n % 5)
        val th5i = n % 7
        if (de9 !in parts.indices) return null
        val opStr = parts.removeAt(de9)
        if (th5i !in parts.indices) return null
        val hashStr = parts.removeAt(th5i) +
            if (body.contains("self.location")) hostname else ""
        var data = parts.joinToString("")

        val pair = Regex("""var\s+(\w+)\s*=\s*\w+\.splice\([^)]+\)\s*\[0\]\s*,\s*(?:var\s+)?(\w+)\s*=\s*\w+\.splice""")
            .find(body)
        val opVar = pair?.groupValues?.get(1)
            ?: Regex("""var\s+(\w+)\s*=\s*\w+\.splice""").find(body)?.groupValues?.get(1)
            ?: return null
        val hashVar = pair?.groupValues?.get(2)
            ?: Regex(""",\s*(?:var\s+)?(\w+)\s*=\s*\w+\.splice""").find(body)?.groupValues?.get(1)
            ?: return null

        data class Check(val pos: Int, val vref: String, val thr: Int, val target: String, val expr: String)
        val checks = Regex("""if\s*\(\s*(\w+)\.length\s*>\s*(\d+)\s*\)\s*\{\s*(\w+)\s*=\s*([^;]+);?\s*\}""")
            .findAll(body).map {
                Check(
                    it.range.first,
                    it.groupValues[1],
                    it.groupValues[2].toIntOrNull() ?: 0,
                    it.groupValues[3],
                    it.groupValues[4].trim()
                )
            }.toList()
        val loopPos = Regex("""for\s*\(\s*\w+\s*=\s*${Regex.escape(opVar)}\.length\s*-\s*1""")
            .find(body)?.range?.first ?: body.length
        val dataVar = Regex("""var\s+(\w+)\s*=\s*\w+\.join\(\s*''\s*\)""")
            .find(body)?.groupValues?.get(1) ?: return null

        fun refLen(vref: String): Int = when (vref) {
            opVar -> opStr.length
            hashVar -> hashStr.length
            else -> 0
        }

        fun applyExpr(expr: String, cur: String): String? = when {
            expr.startsWith("atob") -> b64Binary(cur)
            expr.contains("reverse") -> cur.reversed()
            expr.contains("replace") && expr.contains("'0'") ->
                cur.map { if (it.isLetter()) '0' else it }.joinToString("")
            else -> null
        }

        for (c in checks.sortedBy { it.pos }) {
            if (c.pos >= loopPos || c.target != dataVar) continue
            if (refLen(c.vref) > c.thr) {
                data = applyExpr(c.expr, data) ?: continue
            }
        }

        var h = 0
        var x = 0
        for (i in hashStr.indices) {
            val e = hashStr[i].code
            h = (h * 37 + e) % 241
            x = (x + ((e shl 1) xor i)) and 255
        }
        val xorSeed = (h * 3 + x) % 256
        val xorStep = (x % 11) + 5
        var fy = ((x * 251 + h) % 65519) + 1

        for (i in opStr.length - 1 downTo 0) {
            when (val ch = opStr[i]) {
                '7' -> data = b64Binary(data) ?: return null
                '3' -> data = data.reversed()
                else -> {
                    val shift = (26 - ((ch.code - 96) % 26)) % 26
                    data = rotLetters(data, shift)
                }
            }
        }

        for (c in checks.sortedBy { it.pos }) {
            if (c.pos <= loopPos || c.target != dataVar) continue
            val ref = refLen(c.vref).takeIf { it > 0 } ?: data.length
            if (ref > c.thr) {
                data = applyExpr(c.expr, data) ?: continue
            }
        }

        val len = data.length
        val tbl = IntArray(len)
        for (b in len - 1 downTo 1) {
            fy = (fy * 97 + 41) % 65519
            tbl[b] = fy % (b + 1)
        }
        val arr = data.toCharArray()
        for (b in 1 until len) {
            val j = tbl[b]
            val t = arr[b]; arr[b] = arr[j]; arr[j] = t
        }
        data = String(arr)

        val sb = StringBuilder(data.length)
        var acc = xorSeed
        for (ch in data) {
            val e = ch.code
            acc = (acc * 5 + xorStep) % 256
            sb.append((e xor acc).toChar())
            acc = (acc + e) % 256
        }
        return sb.toString().takeIf { it.startsWith("http") }
    }

    private fun b64Binary(s: String): String? {
        val clean = s.replace('-', '+').replace('_', '/')
        val pad = (4 - clean.length % 4) % 4
        return runCatching {
            String(Base64.decode(clean + "=".repeat(pad), Base64.DEFAULT), Charsets.ISO_8859_1)
        }.getOrNull()
    }

    // ---- Subtitle extraction ----

    private suspend fun parseEmbedSubtitles(html: String, referer: String, subtitleCallback: (SubtitleFile) -> Unit) {
        val seen = HashSet<String>()
        Regex(""""file"\s*:\s*"([^"]+\.vtt[^"]*)"[^}]*?"label"\s*:\s*"([^"]+)"""").findAll(html).forEach { m ->
            val rawUrl = m.groupValues[1]
            val lang = m.groupValues[2]
            val url = rawUrl.replace("\\/", "/").replace("\\u0026", "&").replace("\\", "")
            if (url.startsWith("http") && seen.add(url)) {
                subtitleCallback.invoke(
                    newSubtitleFile(lang = lang, url = url) {
                        headers = mapOf("Referer" to referer)
                    }
                )
            }
        }
    }

    private suspend fun parseM3u8Subtitles(m3u8Url: String, referer: String, subtitleCallback: (SubtitleFile) -> Unit) {
        val body = runCatching {
            app.get(m3u8Url, referer = referer, interceptor = interceptor, timeout = REQUEST_TIMEOUT_S).text
        }.getOrNull() ?: return
        Regex("""#EXT-X-MEDIA:TYPE=SUBTITLES[^#]*?URI="([^"]+)"[^#]*?LANGUAGE="([^"]+)"""").findAll(body).forEach { m ->
            val lang = m.groupValues[2]
            val rawUri = m.groupValues[1]
            val url = if (rawUri.startsWith("http")) rawUri
            else fixUrl("${m3u8Url.substringBeforeLast("/")}/$rawUri")
            subtitleCallback.invoke(
                newSubtitleFile(lang = lang, url = url) {
                    headers = mapOf("Referer" to referer)
                }
            )
        }
    }

    // ---- Embed JS decoding ----

    private fun extractFunctionBody(js: String, fn: String): String? {
        val m = Regex("""(?:function\s+${Regex.escape(fn)}\s*\(|var\s+${Regex.escape(fn)}\s*=\s*function\s*\()""")
            .find(js) ?: return null
        val openIdx = js.indexOf('{', m.range.last)
        if (openIdx < 0) return null
        var depth = 0
        var closeIdx = -1
        for (j in openIdx until js.length) {
            when (js[j]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) { closeIdx = j; break }
                }
            }
        }
        if (closeIdx == -1) return null
        return js.substring(openIdx, closeIdx + 1)
    }

    private fun runEmbedDecode(chunks: List<String>, fnBody: String): String? {
        var data = chunks.joinToString("").replace("\\/", "/").toByteArray(Charsets.ISO_8859_1)
        var pos = 0
        while (true) {
            val loopIdx = Regex("""for\s*\(\s*let\s*i""").find(fnBody, pos)?.range?.first ?: -1

            data class Op(val idx: Int, val kind: Int)

            val candidates = mutableListOf<Op>()
            listOf("atob(", "reverse(", "replace(/[a-zA-Z]/g").forEachIndexed { kindIdx, tok ->
                val i = fnBody.indexOf(tok, pos)
                if (i >= 0) candidates.add(Op(i, kindIdx))
            }
            if (candidates.isEmpty()) break
            val op = candidates.minByOrNull { it.idx } ?: break
            if (loopIdx >= 0 && op.idx > loopIdx) break
            pos = op.idx + 1

            when (op.kind) {
                0 -> data = b64(data) ?: return null
                1 -> data = data.reversedArray()
                else -> {
                    val shift = Regex("""base\s*\+\s*(\d+)\s*\)\s*%\s*26""").find(fnBody, pos - 1)
                        ?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    data = rotLetters(String(data, Charsets.ISO_8859_1), shift).toByteArray(Charsets.ISO_8859_1)
                }
            }
        }

        val out = StringBuilder()
        if (fnBody.contains("^") && fnBody.contains("acc")) {
            val seed = Regex("""acc\s*=\s*(\d+)""").find(fnBody)?.groupValues?.get(1)?.toIntOrNull() ?: 109
            val step = Regex("""acc\s*=\s*\(\s*acc\s*\+\s*(\d+)\s*\)""").find(fnBody)?.groupValues?.get(1)?.toIntOrNull() ?: 9
            var acc = seed
            for (b0 in data) {
                val byte = b0.toInt() and 0xFF
                acc = (acc + step) % 256
                val plain = byte xor acc
                acc = (acc + byte) % 256
                out.append(plain.toChar())
            }
        } else {
            val seed = Regex("""(\d+)\s*%\s*\(\s*i""").find(fnBody)?.groupValues?.get(1)?.toIntOrNull() ?: 987647084
            val addend = Regex("""\(\s*i\s*\+\s*(\d+)\s*\)""").find(fnBody)?.groupValues?.get(1)?.toIntOrNull() ?: 18
            for (i in data.indices) {
                val delta = seed % (i + addend)
                val x = (data[i].toInt() and 0xFF) - delta
                out.append((((x % 256) + 256) % 256).toChar())
            }
        }
        return out.toString()
    }

    private fun b64(bytes: ByteArray): ByteArray? {
        val pad = (4 - (bytes.size % 4)) % 4
        val padded = if (pad == 0) bytes else bytes + ByteArray(pad) { '='.code.toByte() }
        return runCatching { Base64.decode(padded, Base64.DEFAULT) }.getOrNull()
    }

    private fun rotLetters(s: String, shift: Int): String {
        val sb = StringBuilder()
        for (c in s) {
            when {
                c in 'a'..'z' -> sb.append(((c.code - 'a'.code + shift).mod(26) + 'a'.code).toChar())
                c in 'A'..'Z' -> sb.append(((c.code - 'A'.code + shift).mod(26) + 'A'.code).toChar())
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun decodePackers(html: String): String {
        var out = html
        while (true) {
            val start = PACKER_START.find(out) ?: return out
            val startIdx = start.range.first
            var depth = 0
            var endIdx = -1
            for (j in startIdx until out.length) {
                when (out[j]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) { endIdx = j; break }
                    }
                }
            }
            if (endIdx == -1) return out
            val seg = out.substring(startIdx, endIdx + 1)
            val m = PACKER_ARGS.find(seg) ?: return out
            val p = m.groupValues[1]
            val a = m.groupValues[2].toInt()
            val keys = m.groupValues[4].split("|")
            val tokenToKey = HashMap<String, String>()
            for (idx in keys.indices) {
                tokenToKey[packerToken(idx, a)] = keys[idx]
            }
            var body = p
            for (idx in keys.size - 1 downTo 0) {
                val tok = packerToken(idx, a)
                val repl = tokenToKey[tok] ?: tok
                if (repl != tok && repl.isNotEmpty()) {
                    body = body.replace(Regex("\\b" + Regex.escape(tok) + "\\b"), repl)
                }
            }
            out = out.substring(0, startIdx) + body + out.substring(endIdx + 1)
        }
    }

    private fun packerToken(n: Int, a: Int): String {
        val prefix = if (n >= a) packerToken(n / a, a) else ""
        val r = n % a
        val last = if (r > 35) (r + 29).toChar().toString()
        else "0123456789abcdefghijklmnopqrstuvwxyz"[r].toString()
        return prefix + last
    }

    companion object {
        private const val TAG = "Cinehd"

        private const val REQUEST_TIMEOUT_S = 30L

        private const val SOLVE_INTERVAL_MS = 30_000L

        private const val CHALLENGE_TIMEOUT_MS = 20_000L

        private const val CHALLENGE_PEEK_LIMIT = 64 * 1024L

        private val PACKER_START = Regex("""eval\(function\(p,a,c,k,e,d\)\{""")
        private val PACKER_ARGS = Regex(
            """\('(.+)',(\d+),(\d+),'(.+)'\.split\('\|'\),\s*0,\s*\{\}\)\)""",
            RegexOption.DOT_MATCHES_ALL
        )
        private val ASSIGN_REGEX = Regex(
            """var\s+(\w+)\s*=\s*(\w+)\s*\(\s*\[([^\]]*)\]"""
        )
    }
}
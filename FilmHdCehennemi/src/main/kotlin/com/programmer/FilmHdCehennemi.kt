// ! Bu araç @programmer tarafından.

package com.programmer

import android.util.Base64
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class FilmHdCehennemi : MainAPI() {
    override var mainUrl              = "https://www.hdfilmcehennemi.nl"
    override var name                 = "FilmHdCehennemi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    private val mapper by lazy { jacksonObjectMapper() }
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val doc = org.jsoup.Jsoup.parse(response.peekBody(10 * 1024).string())
            if (response.code == 503 || doc.selectFirst("meta[name='cloudflare']") != null) {
                return cloudflareKiller.intercept(chain)
            }
            return response
        }
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
        val document = app.get(url, interceptor = interceptor).document
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
        val text = app.get(
            "${mainUrl}/search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8.name()),
            headers = mapOf("X-Requested-With" to "fetch", "Content-Type" to "application/json"),
            interceptor = interceptor
        ).text
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
        val document = app.get(url, interceptor = interceptor).document
        val jsonLd = document.selectFirst("script[type='application/ld+json']")
        val root = runCatching { jsonLd?.data()?.let { mapper.readTree(it) } }.getOrNull()

        val title = root?.get("name")?.asText(null)
            ?: document.selectFirst("h1.section-title, h1[class*='title']")?.text()
                ?.replace(Regex("""\s+<small>.*""", RegexOption.DOT_MATCHES_ALL), "")
                ?.trim()
            ?: document.selectFirst("title")?.text()?.trim()
                ?.replace(" izle | Hdfilmcehennemi | Film izle | HD Film izle", "")
                ?.replace(" | Hdfilmcehennemi", "")?.trim()
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
        // ISO8601: "PT1H30M", "PT92M", "PT0M"
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
        val document = app.get(data, interceptor = interceptor).document
        val iframeEl = document.selectFirst("iframe[data-src], iframe[src]") ?: return false
        val embedUrl = fixUrl(iframeEl.attr("data-src").ifBlank { iframeEl.attr("src") })
        return resolveEmbed(embedUrl, data, subtitleCallback, callback)
    }

    private suspend fun resolveEmbed(
        embedUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val page = app.get(embedUrl, referer = referer, interceptor = interceptor).text
        val decoded = decodePackers(page)

        parseEmbedSubtitles(page, embedUrl, subtitleCallback)
        parseEmbedSubtitles(decoded, embedUrl, subtitleCallback)

        for (assign in ASSIGN_REGEX.findAll(decoded)) {
            val fn = assign.groupValues[2]
            val chunkStr = assign.groupValues[3]
            val chunks = Regex("\"([^\"]+)\"").findAll(chunkStr).map { it.groupValues[1] }.toList()
                .map { it.replace("\\/", "/") }
            if (chunks.isEmpty()) continue
            val fnBody = extractFunctionBody(decoded, fn) ?: continue
            val videoUrl = runEmbedDecode(chunks, fnBody) ?: continue
            if (!videoUrl.startsWith("http")) continue

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
                    headers = mapOf("Referer" to embedUrl)
                }
            )
            return true
        }

        return runCatching { loadExtractor(embedUrl, mainUrl, {}, callback) }.getOrDefault(false)
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
        val body = runCatching { app.get(m3u8Url, referer = referer, interceptor = interceptor).text }
            .getOrNull() ?: return
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

    // ---- Embed (rapidrame) JS decoding ----

    private fun extractFunctionBody(js: String, fn: String): String? {
        val m = Regex("function\\s+$fn\\s*\\(").find(js) ?: return null
        val openIdx = js.indexOf('{', m.range.last) ?: return null
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
        return js.substring(openIdx + 1, closeIdx)
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

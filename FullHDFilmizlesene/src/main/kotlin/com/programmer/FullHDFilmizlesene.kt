// ! Bu araç @programmer tarafından.

package com.programmer

import android.util.Base64
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class FullHDFilmizlesene : MainAPI() {
    override var mainUrl              = "https://www.fullhdfilmizlesene.now"
    override var name                 = "FullHDFilmizlesene"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Movie)

    private val mapper by lazy { jacksonObjectMapper() }

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
    )

    private suspend fun req(url: String, ref: String? = null) = app.get(
        url,
        referer = ref ?: "$mainUrl/",
        headers = commonHeaders,
    )

    override val mainPage = mainPageOf(
        "${mainUrl}/yeni-filmler/SAYFA"              to "Yeni Filmler",
        "${mainUrl}/en-cok-izlenen-filmler/SAYFA"    to "En Çok İzlenen Filmler",
        "${mainUrl}/populer-filmler/SAYFA"           to "Popüler Filmler",
        "${mainUrl}/trend-filmler/SAYFA"             to "Trend Filmler",
        "${mainUrl}/filmizle/yerli-filmler/SAYFA"    to "Yerli Filmler",
        "${mainUrl}/filmizle/yabanci-filmler/SAYFA"  to "Yabancı Filmler",
        "${mainUrl}/filmizle/aksiyon-filmleri/SAYFA" to "Aksiyon",
        "${mainUrl}/filmizle/komedi-filmleri/SAYFA"  to "Komedi",
        "${mainUrl}/filmizle/korku-filmleri/SAYFA"   to "Korku",
        "${mainUrl}/filmizle/dram-filmler-izle/SAYFA" to "Dram",
        "${mainUrl}/filmizle/bilim-kurgu-filmleri/SAYFA" to "Bilim Kurgu",
        "${mainUrl}/filmizle/gerilim-filmleri/SAYFA" to "Gerilim",
        "${mainUrl}/filmizle/romantik-filmler/SAYFA" to "Romantik",
        "${mainUrl}/filmizle/macera-filmleri/SAYFA"  to "Macera",
        "${mainUrl}/filmizle/animasyon-filmleri/SAYFA" to "Animasyon",
        "${mainUrl}/filmizle/turkce-dublaj-filmler-1/SAYFA" to "Türkçe Dublaj",
        "${mainUrl}/filmizle/turkce-altyazili-filmler-1/SAYFA" to "Türkçe Altyazılı",
        "${mainUrl}/filmizle/4k-filmler/SAYFA"       to "4K Filmler",
        "${mainUrl}/yil/2026-filmleri-izle/SAYFA"    to "2026 Filmleri",
        "${mainUrl}/yil/2025-filmler-izle/SAYFA"     to "2025 Filmleri",
        "${mainUrl}/yil/2024-filmleri-izle-1/SAYFA"  to "2024 Filmleri",
        "${mainUrl}/seri-filmler/SAYFA"              to "Seri Filmler",
        "${mainUrl}/film-listeleri/SAYFA"            to "Film Listeleri",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.removeSuffix("/SAYFA")
        val url  = if (page <= 1) base else "$base/$page"
        val document = req(url).document
        val home     = document.parseFilms()
        val hasNext  = document.selectFirst("div.sayfalama a.ileri, link[rel=next]") != null
        return newHomePageResponse(request.name, home, hasNext)
    }

    private fun Document.parseFilms(): List<SearchResponse> {
        val seen  = mutableSetOf<String>()
        val items = (select("ul.list li.film") + select("li.film") + select("div.film"))
            .distinct()
            .mapNotNull { it.toSearchResult() }
            .filter { seen.add(it.url) }
        return items
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a.tt") ?: this.selectFirst("a[href*='/film/']") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        val title = this.selectFirst("span.film-title")?.text()?.trim()
            ?: link.attr("title").trim()
            ?: link.text().trim().removeSuffix(" izle").trim()
        if (title.isEmpty()) return null

        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.let { img ->
                (img.attr("data-srcset").substringBefore(" ").takeIf { it.isNotBlank() && !it.startsWith("data:") })
                    ?: img.attr("data-src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
                    ?: img.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
                    ?: this.selectFirst("source")?.attr("data-srcset")?.substringBefore(" ")
            }
        )

        val year   = this.selectFirst("span.film-yil")?.text()?.trim()?.toIntOrNull()
        val rating = this.selectFirst("span.imdb")?.text()?.trim()?.toFloatOrNull()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.year      = year
            this.score     = rating?.let { Score.from10(it) }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = req("${mainUrl}/arama/${query}").document
        return document.parseFilms()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = req(url).document

        var title: String? = null
        var poster: String? = null
        var year: Int? = null
        var plot: String? = null
        var rating: Double? = null
        var duration: Int? = null
        var trailer: String? = null
        val tags = mutableListOf<String>()
        val actors = mutableListOf<Actor>()

        document.select("script[type=application/ld+json]").forEach { script ->
            val node = runCatching { mapper.readTree(script.html()) }.getOrNull() ?: return@forEach
            if (node.path("@type").asText() != "Movie") return@forEach

            title  = node.path("name").asText(null)?.ifBlank { null } ?: title
            poster = node.path("image").asText(null)?.ifBlank { null } ?: poster
            plot   = node.path("description").asText(null)?.ifBlank { null } ?: plot
            year   = node.path("datePublished").asText(null)
                ?.take(4)?.toIntOrNull() ?: year
            rating = node.path("aggregateRating").path("ratingValue").asDouble(0.0)
                .takeIf { it > 0.0 } ?: rating
            duration = parseDurationMinutes(node.path("duration").asText(null)
                ?: node.path("timeRequired").asText(null)) ?: duration
            trailer = node.path("trailer").path("thumbnailUrl").asText(null)?.ifBlank { null }
                ?: trailer

            node.path("actor").let { arr ->
                if (arr.isArray) arr.forEach { a ->
                    a.path("name").asText(null)?.ifBlank { null }?.let { actors.add(Actor(it)) }
                } else if (arr.isObject) {
                    arr.path("name").asText(null)?.ifBlank { null }?.let { actors.add(Actor(it)) }
                }
            }
        }

        if (title.isNullOrBlank()) {
            title = document.selectFirst("div.izle-titles h1, h1")?.text()?.trim()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore(" Film izle")?.trim()
        }
        if (title.isNullOrBlank()) return null

        if (poster.isNullOrBlank()) {
            poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        }
        if (plot.isNullOrBlank()) {
            plot = document.selectFirst("div.ozet-ic, div.film-ozeti")?.text()?.trim()
                ?: document.selectFirst("meta[name=description]")?.attr("content")
        }
        if (year == null) {
            year = document.selectFirst("span.film-yil, a[href*='/yil/']")?.text()?.trim()?.toIntOrNull()
        }
        if (rating == null) {
            rating = document.selectFirst("span.imdb, div.puanx-puan")?.text()
                ?.replace(',', '.')?.toFloatOrNull()?.toDouble()
        }

        document.select("a[rel=category tag], a[href*='/filmizle/']").forEach {
            val t = it.text().trim()
            if (t.isNotEmpty() && t !in tags) tags.add(t)
        }

        if (trailer.isNullOrBlank()) {
            trailer = document.selectFirst("div.ozet-ic iframe[src*=youtu], meta[property='og:video']")?.let {
                it.attr("src").ifBlank { it.attr("content") }
            }?.ifBlank { null }
        }

        val recommendations = document.select("li.film, div.film").mapNotNull {
            it.toSearchResult()?.takeIf { r -> r.url != url }
        }.distinctBy { it.url }.take(20)

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl       = poster
            this.year            = year
            this.plot            = plot
            this.score           = rating?.let { Score.from10(it.toFloat()) }
            this.duration        = duration
            this.tags            = tags
            this.recommendations = recommendations
            addActors(actors)
            addTrailer(trailer)
        }
    }

    private fun parseDurationMinutes(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        // PT145M or PT2H25M
        val h = Regex("""(\d+)H""").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val m = Regex("""(\d+)M""").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val total = h * 60 + m
        return total.takeIf { it > 0 }
    }

    private fun rot13(s: String): String = buildString(s.length) {
        for (c in s) {
            append(
                when (c) {
                    in 'a'..'z' -> ((c - 'a' + 13) % 26 + 'a'.code).toChar()
                    in 'A'..'Z' -> ((c - 'A' + 13) % 26 + 'A'.code).toChar()
                    else -> c
                }
            )
        }
    }

    private fun b64Decode(s: String): String? = try {
        val clean = s.replace('-', '+').replace('_', '/')
        val pad   = (4 - clean.length % 4) % 4
        String(Base64.decode(clean + "=".repeat(pad), Base64.DEFAULT), Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }

    private fun decodeScxToken(token: String): String? = b64Decode(rot13(token))

    private fun decodeP8(p8: String): String? = try {
        val step1 = b64Decode(p8.reversed()) ?: return null
        val key   = "K9L"
        val mid   = StringBuilder(step1.length)
        for (i in step1.indices) {
            val a = key[i % 3].code
            mid.append((step1[i].code - (a % 5 + 1)).toChar())
        }
        b64Decode(mid.toString())
    } catch (_: Exception) {
        null
    }

    private fun collectScxTokens(html: String): List<String> {
        val tokens = mutableListOf<String>()
        Regex("""scx\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
            ?.let { raw ->
                runCatching { mapper.readTree(raw) }.getOrNull()?.let { root ->
                    root.fields().forEach { (_, node) ->
                        val sx = node.path("sx")
                        collectTokensFromSx(sx, tokens)
                    }
                }
            }
        return tokens.distinct()
    }

    private fun collectTokensFromSx(sx: JsonNode, out: MutableList<String>) {
        val t = sx.path("t")
        when {
            t.isArray  -> t.forEach { n -> n.asText(null)?.takeIf { it.isNotBlank() }?.let(out::add) }
            t.isTextual -> t.asText().takeIf { it.isNotBlank() }?.let(out::add)
        }
        val p = sx.path("p")
        if (p.isArray) {
            p.forEach { part ->
                when {
                    part.isArray  -> part.forEach { n -> n.asText(null)?.takeIf { it.isNotBlank() }?.let(out::add) }
                    part.isTextual -> part.asText().takeIf { it.isNotBlank() }?.let(out::add)
                }
            }
        }
    }

    private suspend fun extractFromEmbed(embedUrl: String, sourceName: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val html = req(embedUrl).text
        val p8 = Regex("""window\._p8\s*=\s*'([^']+)'""").find(html)?.groupValues?.get(1)
            ?: Regex("""window\._p8\s*=\s*"([^"]+)""").find(html)?.groupValues?.get(1)
            ?: return false

        val decoded = decodeP8(p8) ?: return false
        val root    = runCatching { mapper.readTree(decoded) }.getOrNull() ?: return false

        val streams = listOfNotNull(
            root.path("cm").asText(null)?.ifBlank { null },
            root.path("tm").asText(null)?.ifBlank { null },
        ).distinct()

        var emitted = false
        streams.forEachIndexed { index, streamUrl ->
            if (!streamUrl.startsWith("http")) return@forEachIndexed
            callback.invoke(
                newExtractorLink(
                    source = sourceName,
                    name = if (index == 0) sourceName else "$sourceName (Alt)",
                    url = streamUrl,
                    type = ExtractorLinkType.M3U8,
                ) {
                    headers = mapOf(
                        "Referer" to embedUrl,
                        "Origin" to mainUrl,
                    )
                    quality = if (index == 0) getQualityFromName("1080p") else getQualityFromName("720p")
                }
            )
            emitted = true
        }

        root.path("ct").let { ct ->
            if (ct.isArray) {
                ct.forEach { track ->
                    if (track.path("kind").asText() != "captions") return@forEach
                    val file  = track.path("file").asText(null)?.ifBlank { null } ?: return@forEach
                    val label = track.path("label").asText("Altyazı").ifBlank { "Altyazı" }
                    subtitleCallback.invoke(SubtitleFile(label, file))
                }
            }
        }

        return emitted
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val document = req(data).document
        val tokens   = collectScxTokens(document.html())
        if (tokens.isEmpty()) return false

        val embedUrls = tokens.mapNotNull { decodeScxToken(it) }
            .filter { it.startsWith("http") }
            .distinct()

        var any = false
        for (embed in embedUrls) {
            val host = runCatching {
                java.net.URI(embed).host?.removePrefix("www.") ?: embed
            }.getOrDefault(embed)
            if (extractFromEmbed(embed, host, subtitleCallback, callback)) {
                any = true
            }
        }
        return any
    }
}

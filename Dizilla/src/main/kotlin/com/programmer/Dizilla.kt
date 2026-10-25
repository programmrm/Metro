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
import java.net.URLEncoder

class Dizilla : MainAPI() {
    override var mainUrl = "https://dizilla.now"
    override var name = "Dizilla"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 200L
    override var sequentialMainPageScrollDelay = 200L

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = chain.proceed(request)
            val doc = Jsoup.parse(response.peekBody(1024 * 1024).string())
            if (doc.html().contains("Just a moment")) {
                return cloudflareKiller.intercept(chain)
            }
            return response
        }
    }

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to mainUrl,
    )

    private val mapper by lazy {
        ObjectMapper().registerModule(KotlinModule.Builder().build()).apply {
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }
    }

    private val homeSections = setOf("Öne Çıkanlar", "Güncel Bölümler", "Yeni Eklenenler")

    override val mainPage = mainPageOf(
        "$mainUrl/?sec=1" to "Öne Çıkanlar",
        "$mainUrl/?sec=2" to "Güncel Bölümler",
        "$mainUrl/?sec=3" to "Yeni Eklenenler",
        "$mainUrl/tum-bolumler" to "Yeni Bölümler",
        "$mainUrl/yabanci-dizi-izle" to "Yabancı Diziler",
        "$mainUrl/turkce-dublaj-dizi-izle" to "Türkçe Dublaj",
        "$mainUrl/yerli-dizi-izle" to "Yerli Diziler",
        "$mainUrl/kdrama-izle" to "Kore Dizileri",
        "$mainUrl/anime-izle" to "Anime",
    )

    private suspend fun fetch(url: String): Document? {
        return try {
            app.get(url, interceptor = interceptor, headers = commonHeaders).document
        } catch (e: Exception) {
            Log.e("Dizilla", "fetch error ($url): ${e.message}")
            null
        }
    }

    private fun absUrl(href: String): String = when {
        href.startsWith("http://") || href.startsWith("https://") -> href
        href.startsWith("//") -> "https:$href"
        href.startsWith("/") -> mainUrl + href
        else -> "$mainUrl/$href"
    }

    private fun cleanPoster(src: String?): String? {
        val value = src?.substringBefore("#")?.trim().orEmpty()
        if (value.isEmpty()) return null
        return absUrl(value)
    }

    private fun parseCards(doc: Document, selector: String = "a.pcard[href]"): List<SearchResponse> {
        val seen = mutableSetOf<String>()
        val items = mutableListOf<SearchResponse>()

        for (card in doc.select(selector)) {
            val path = card.attr("href")
            if (!path.startsWith("/dizi/")) continue
            val url = absUrl(path)
            if (!seen.add(url)) continue

            val label = card.attr("aria-label")
            val title = card.selectFirst(".mvk-t")?.text()?.trim()
                ?: label.substringBefore(",").trim()
            if (title.isEmpty()) continue

            val meta = card.selectFirst(".mvk-m")?.text()?.trim() ?: label
            val year = YEAR_REGEX.find(meta)?.value?.toIntOrNull()
            val rating = RATING_REGEX.find(meta)?.groupValues?.getOrNull(1)?.toFloatOrNull()
                ?: IMDB_REGEX.find(meta)?.groupValues?.getOrNull(1)?.toFloatOrNull()

            items.add(
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    this.posterUrl = cleanPoster(card.selectFirst("img")?.attr("src"))
                    this.year = year
                    this.score = rating?.let { Score.from10(it) }
                }
            )
        }
        return items
    }

    private fun parseFeatured(doc: Document): List<SearchResponse> {
        val seen = mutableSetOf<String>()
        val items = mutableListOf<SearchResponse>()

        for (item in doc.select("section[aria-roledescription=\"carousel\"] div.dsl-it")) {
            val link = item.selectFirst("a[href^=\"/dizi/\"]") ?: continue
            val url = absUrl(link.attr("href"))
            if (!seen.add(url)) continue

            val title = item.selectFirst(".dsl-n")?.text()?.trim()
                ?: link.attr("aria-label").substringBefore(",").trim()
            if (title.isEmpty()) continue

            val rating = item.selectFirst(".dsl-imdb")?.text()?.trim()?.toFloatOrNull()
            items.add(
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    this.posterUrl = cleanPoster(item.selectFirst("img")?.attr("src"))
                    this.score = rating?.let { Score.from10(it) }
                }
            )
        }
        return items
    }

    private fun parseEpisodeCards(doc: Document, selector: String = "a.gb[href]"): List<SearchResponse> {
        val seen = mutableSetOf<String>()
        val items = mutableListOf<SearchResponse>()

        for (card in doc.select(selector)) {
            val path = card.attr("href")
            if (!path.contains("-sezon-")) continue
            val url = absUrl(path)
            if (!seen.add(url)) continue

            val label = card.attr("aria-label")
            val title = label.substringBefore(",").trim().ifEmpty {
                EPISODE_TITLE_REGEX.find(card.text())?.groupValues?.getOrNull(1)?.trim().orEmpty()
            }
            if (title.isEmpty()) continue

            items.add(
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    this.posterUrl = cleanPoster(card.selectFirst("img")?.attr("src"))
                }
            )
        }
        return items
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val name = request.name
        val base = request.data.substringBefore("?")

        try {
            if (name in homeSections) {
                if (page > 1) return newHomePageResponse(name, emptyList(), false)
                val doc = fetch(base) ?: return newHomePageResponse(name, emptyList(), false)
                val items = when (name) {
                    "Öne Çıkanlar" -> parseFeatured(doc)
                    "Güncel Bölümler" -> parseEpisodeCards(
                        doc, "section[aria-labelledby=\"h-week\"] a.gb[href]"
                    ).ifEmpty { parseEpisodeCards(doc) }
                    else -> parseCards(doc, "section[aria-labelledby=\"h-six\"] a.pcard[href]")
                        .ifEmpty { parseCards(doc) }
                }
                Log.d("Dizilla", "$name: ${items.size} items")
                return newHomePageResponse(name, items, false)
            }

            val url = if (page > 1) "$base?page=$page" else base
            val doc = fetch(url) ?: return newHomePageResponse(name, emptyList(), false)
            val items = parseCards(doc).ifEmpty { parseEpisodeCards(doc) }
            val hasNext = doc.selectFirst("a[rel=next]") != null
            Log.d("Dizilla", "$name (sayfa $page): ${items.size} items, hasNext=$hasNext")
            return newHomePageResponse(name, items, hasNext)
        } catch (e: Exception) {
            Log.e("Dizilla", "getMainPage error: ${e.message}")
            return newHomePageResponse(name, emptyList(), false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val url = "$mainUrl/ara?q=" + URLEncoder.encode(query.trim(), "UTF-8")
            val doc = fetch(url) ?: return emptyList()
            parseCards(doc)
        } catch (e: Exception) {
            Log.e("Dizilla", "search error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        return try {
            var target = url
            var doc = fetch(target) ?: return null

            // Bölüm bağlantısı ya da eski biçimdeki (/<slug>) adres dizi sayfasına çevrilir
            if (!target.contains("/dizi/")) {
                val seriesPath = SERIES_HREF_REGEX.find(doc.html())?.groupValues?.getOrNull(1)
                if (seriesPath != null) {
                    target = absUrl(seriesPath)
                    doc = fetch(target) ?: return null
                } else {
                    val slug = target.trimEnd('/').substringAfterLast("/")
                    val alternate = "$mainUrl/dizi/$slug"
                    if (slug.isNotEmpty() && alternate != target) {
                        val altDoc = fetch(alternate)
                        if (altDoc != null && altDoc.selectFirst("script[type=\"application/ld+json\"]") != null) {
                            target = alternate
                            doc = altDoc
                        }
                    }
                }
            }

            return parseSeries(doc, target)
        } catch (e: Exception) {
            Log.e("Dizilla", "load error: ${e.message}")
            null
        }
    }

    private suspend fun parseSeries(doc: Document, url: String): LoadResponse? {
        var jsonLdTitle: String? = null
        var jsonLdPoster: String? = null
        var jsonLdPlot: String? = null
        var jsonLdYear: Int? = null
        var score: Float? = null
        val tags = linkedSetOf<String>()
        val episodes = LinkedHashMap<String, Episode>()
        val seasonUrls = mutableListOf<String>()

        for (script in doc.select("script[type=\"application/ld+json\"]")) {
            val node = runCatching { mapper.readTree(script.data()) }.getOrNull() ?: continue
            if (node.path("@type").asText() != "TVSeries") continue

            if (jsonLdTitle == null) jsonLdTitle = node.path("name").asText(null)?.ifBlank { null }
            if (jsonLdPoster == null) jsonLdPoster = node.path("image").asText(null)?.ifBlank { null }
            if (jsonLdPlot == null) jsonLdPlot = node.path("description").asText(null)?.ifBlank { null }
            if (jsonLdYear == null) jsonLdYear = node.path("datePublished").asText(null)?.take(4)?.toIntOrNull()
            if (score == null) {
                score = node.path("aggregateRating").path("ratingValue").asDouble(0.0)
                    .takeIf { it > 0.0 }?.toFloat()
            }
            node.path("genre").let { genres ->
                if (genres.isArray) {
                    for (genre in genres) {
                        genre.asText(null)?.trim()?.takeIf { it.isNotEmpty() }?.let { tags.add(it) }
                    }
                }
            }

            val seasons = node.path("containsSeason")
            if (!seasons.isArray || episodes.isNotEmpty()) continue
            for ((seasonIndex, season) in seasons.withIndex()) {
                if (!season.isObject) continue
                val seasonNo = season.path("seasonNumber").asInt(seasonIndex + 1)
                season.path("url").asText(null)?.ifBlank { null }?.let { seasonUrls.add(it) }

                val eps = season.path("episode")
                if (!eps.isArray) continue
                for ((epIndex, ep) in eps.withIndex()) {
                    if (!ep.isObject) continue
                    val epUrl = ep.path("url").asText(null)?.ifBlank { null } ?: continue
                    val epNo = ep.path("episodeNumber").asInt(0).takeIf { it > 0 } ?: (epIndex + 1)
                    val absolute = absUrl(epUrl)
                    episodes[absolute] = newEpisode(absolute) {
                        this.name = ep.path("name").asText(null)
                        this.season = seasonNo
                        this.episode = epNo
                    }
                }
            }
        }

        // DOM tarafında duran daha doğru bilgilerle tamamla
        val title = doc.selectFirst("main h1")?.text()?.trim()?.ifEmpty { null } ?: jsonLdTitle
        if (title.isNullOrBlank()) return null

        val heroMeta = doc.selectFirst("main h1 + div")?.text()
        val year = heroMeta?.let { YEAR_REGEX.find(it)?.value?.toIntOrNull() } ?: jsonLdYear

        if (tags.isEmpty()) {
            doc.select("main a[href*=\"/dizi-turu/\"]").forEach { link ->
                link.text().trim().takeIf { it.isNotEmpty() }?.let { tags.add(it) }
            }
        }

        val plot = doc.selectFirst("main h2#hk-b + p")?.text()?.trim()?.ifEmpty { null }
            ?: doc.selectFirst("main p[style*=\"line-height\"]")?.text()?.trim()?.ifEmpty { null }
            ?: jsonLdPlot

        val poster = cleanPoster(doc.selectFirst("main img[src*=\"256/384\"]")?.attr("src"))
            ?: cleanPoster(jsonLdPoster)
            ?: cleanPoster(doc.selectFirst("main img[src]")?.attr("src"))

        // JSON-LD bölüm listesi içermiyorsa sezon sayfalarından kur
        if (episodes.isEmpty()) {
            episodesFromSeasons(seasonUrls).forEach { (epUrl, episode) ->
                episodes.putIfAbsent(epUrl, episode)
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.values.toList()) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags.toList()
            this.score = score?.let { Score.from10(it) }
        }
    }

    private suspend fun episodesFromSeasons(seasonUrls: List<String>): Map<String, Episode> {
        val episodes = LinkedHashMap<String, Episode>()

        for (seasonUrl in seasonUrls.take(10)) {
            val seasonDoc = fetch(absUrl(seasonUrl)) ?: continue
            for (link in seasonDoc.select("a[href]")) {
                val href = link.attr("href")
                val match = EPISODE_PATH_REGEX.find(href) ?: continue
                val url = absUrl(href.substringBefore("#"))
                if (episodes.containsKey(url)) continue

                val label = link.attr("aria-label")
                episodes[url] = newEpisode(url) {
                    this.name = label.substringBefore(",").trim().ifEmpty { null }
                    this.season = match.groupValues[1].toIntOrNull()
                    this.episode = match.groupValues[2].toIntOrNull()
                }
            }
        }
        return episodes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            Log.d("Dizilla", "loadLinks: $data")
            val html = app.get(data, interceptor = interceptor, headers = commonHeaders).text

            val seen = mutableSetOf<String>()
            var created = false
            for (source in extractSources(html)) {
                if (source.trailer) continue
                if (!seen.add(source.src)) continue
                if (loadExtractor(source.src, "$mainUrl/", subtitleCallback, callback)) created = true
            }

            if (!created) {
                val iframe = IFRAME_REGEX.find(html)?.groupValues?.getOrNull(1)
                if (iframe != null) {
                    created = loadExtractor(absUrl(iframe), "$mainUrl/", subtitleCallback, callback)
                }
            }

            Log.d("Dizilla", "loadLinks sonuc: $created (kaynak: ${seen.size})")
            created
        } catch (e: Exception) {
            Log.e("Dizilla", "loadLinks error: ${e.message}")
            false
        }
    }

    private data class VideoSource(val src: String, val trailer: Boolean)

    private fun extractSources(html: String): List<VideoSource> {
        val raw = SOURCES_REGEX.find(html)?.groupValues?.getOrNull(1)
            ?: PLAIN_SOURCES_REGEX.find(html)?.groupValues?.getOrNull(1)
            ?: return emptyList()

        val json = raw.replace("\\\"", "\"").replace("\\/", "/")
        val array = runCatching { mapper.readTree(json) }.getOrNull() ?: return emptyList()
        if (!array.isArray) return emptyList()

        val sources = mutableListOf<VideoSource>()
        for (node in array) {
            val src = node.path("src").asText(null)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            sources.add(VideoSource(src, node.path("trailer").asBoolean(false)))
        }
        return sources
    }

    companion object {
        private val YEAR_REGEX = Regex("""\b(19\d{2}|20\d{2})\b""")
        private val RATING_REGEX = Regex("""★\s*([0-9]+(?:\.[0-9]+)?)""")
        private val IMDB_REGEX = Regex("""IMDb\s*([0-9]+(?:\.[0-9]+)?)""")
        private val EPISODE_TITLE_REGEX = Regex("""^(.+?)\s+\d+\.\s+Sezon""")
        private val SERIES_HREF_REGEX = Regex("""\\?"seriesHref\\?":\\?"(/dizi/[^"\\]+)""")
        private val EPISODE_PATH_REGEX = Regex("""-(\d+)-sezon-(\d+)-bolum""")
        private val SOURCES_REGEX = Regex("""\\?"sources\\?":\s*(\[[\s\S]*?\]),\\?"hosts\\?""")
        private val PLAIN_SOURCES_REGEX = Regex(""""sources":(\[\{"key"[\s\S]*?\}\])\s*,\s*"hosts"""")
        private val IFRAME_REGEX = Regex("""<iframe[^>]+src=["']([^"']+)["']""")
    }
}

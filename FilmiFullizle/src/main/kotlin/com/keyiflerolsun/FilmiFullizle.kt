// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class FilmiFullizle : MainAPI() {
    override var mainUrl              = "http://filmifullizle.life"
    override var name                 = "FilmiFullizle"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie)

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor by lazy { CloudflareInterceptor(cloudflareKiller) }

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to mainUrl
    )

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = chain.proceed(request)
            val doc = Jsoup.parse(response.peekBody(1024 * 1024).string())
            if (doc.html().contains("verifying") || doc.selectFirst("meta[name='cloudflare']") != null) {
                return cloudflareKiller.intercept(chain)
            }
            return response
        }
    }

    override val mainPage = mainPageOf(
        mainUrl to "Ana Sayfa"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}${if (page <= 1) "" else "?page=$page"}", interceptor = interceptor, headers = commonHeaders).document

        val home = document.select("div.film, article, li.movie, .poster-mb-bx, .item, div.poster-mb-bx").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("a[title], h2 a, h3 a, .title a, .film-title a")?.text()
            ?: this.selectFirst("img[alt]")?.attr("alt")
            ?: return null
        val href = fixUrlNull(this.selectFirst("a[href]")?.attr("href")) ?: return null

        val posterUrl = fixUrlNull(
            this.selectFirst("img[src]")?.attr("src")
                ?: this.selectFirst("img[data-src]")?.attr("data-src")
        )

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=$query", interceptor = interceptor, headers = commonHeaders).document
        return document.select("div.film, article, li.movie, .poster-mb-bx, .item, div.poster-mb-bx").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = interceptor, headers = commonHeaders).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(
            document.selectFirst("div.poster img, .film-poster img, img[src*='film']")?.attr("src")
                ?: document.selectFirst("div.poster img, .film-poster img, img[src*='film']")?.attr("data-src")
        )
        val year = Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
            ?: document.selectFirst("span.year, .date, time")?.text()?.trim()?.toIntOrNull()
        val description = document.selectFirst("p.description, div.description, .film-aciklama p, .ozet p")?.text()?.trim()
            ?: document.selectFirst("meta[name='description']")?.attr("content")
        val tags = document.select("a[href*='kategori'], a[href*='tur'], a[rel='category tag']").map { it.text() }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, interceptor = interceptor, headers = commonHeaders).document

        val iframe = document.selectFirst("iframe[src]")?.attr("src")
            ?: document.selectFirst("div.video iframe, .player iframe, #player iframe")?.attr("src")
            ?: return false

        loadExtractor(fixUrl(iframe), mainUrl, subtitleCallback, callback)
        return true
    }
}

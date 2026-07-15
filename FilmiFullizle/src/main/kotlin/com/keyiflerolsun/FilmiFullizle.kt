// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class FilmiFullizle : MainAPI() {
    override var mainUrl              = "https://www.filmifullizle.life"
    override var name                 = "FilmiFullizle"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        mainUrl to "Ana Sayfa"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}${if (page <= 1) "" else "?page=$page"}").document

        val home = document.select("div.film, article, li.movie, .poster-mb-bx, .item").mapNotNull { it.toSearchResult() }

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
        val document = app.get("${mainUrl}/?s=$query").document
        return document.select("div.film, article, li.movie, .poster-mb-bx, .item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

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
        val document = app.get(data).document

        val iframe = document.selectFirst("iframe[src]")?.attr("src")
            ?: document.selectFirst("div.video iframe, .player iframe, #player iframe")?.attr("src")
            ?: return false

        loadExtractor(fixUrl(iframe), mainUrl, subtitleCallback, callback)
        return true
    }
}

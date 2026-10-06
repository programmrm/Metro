// ! Bu araç @programmer tarafından.

package com.programmer

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.StringUtils.decodeUri
import java.net.URLEncoder
import java.text.Normalizer
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class DiziBox : MainAPI() {
    override var mainUrl              = "https://www.dizibox.live"
    override var name                 = "DiziBox"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries)

    // ! CloudFlare bypass
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller): Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val doc      = Jsoup.parse(response.peekBody(1024 * 1024).string())

            if (doc.html().contains("Just a moment")) {
                return cloudflareKiller.intercept(chain)
            }

            return response
        }
    }

    private fun getDbxCookie(): String {
        return (System.currentTimeMillis() / 1000).toString()
    }

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
    )

    private val commonCookies = mapOf(
        "LockUser"      to "true",
        "isTrustedUser" to "true",
    )

    private suspend fun req(url: String, ref: String? = null) = app.get(
        url,
        referer = ref,
        cookies = commonCookies + mapOf("dbxu" to getDbxCookie()),
        headers = commonHeaders,
        interceptor = interceptor,
    )

    /**
     * Cloudflare, CloudStream'ın afiş görsellerini yüklerken kullandığı User-Agent'ı
     * (Chrome/149.0.0.0) site içi görsellerde 403 ile engelliyor.
     * Bu yüzden posterler DuckDuckGo görsel proxy'si üzerinden isteniyor.
     */
    private fun proxyPoster(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return "https://external-content.duckduckgo.com/iu/?u=" +
            URLEncoder.encode(url.trim(), "UTF-8")
    }

    /** img[data-src] ya da img[src] değerini alır (data: URI placeholder'ları hariç). */
    private fun Element.posterSource(): String? {
        val img = this.selectFirst("img") ?: return null
        return img.attr("data-src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: img.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
    }

    /** div.box-image içindeki background-image: url(...) değerini çeker. */
    private fun Element.backgroundImageUrl(): String? {
        val style = this.selectFirst("div.box-image")?.attr("style") ?: return null
        return Regex("""url\(\s*['"]?([^'")]+)['"]?\s*\)""").find(style)?.groupValues?.get(1)
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/tum-bolumler/page/SAYFA/?tip=populer" to "Popüler Dizilerden Son Bölümler",
        "${mainUrl}/tum-bolumler/page/SAYFA/"              to "Yeni Eklenen Bölümler",
        "${mainUrl}/ulke/turkiye"                          to "Yerli",
        "${mainUrl}/dizi-arsivi/page/SAYFA/"    to "Dizi Arşivi",
        "${mainUrl}/tur/aile/page/SAYFA/"       to "Aile",
        "${mainUrl}/tur/aksiyon/page/SAYFA"     to "Aksiyon",
        "${mainUrl}/tur/animasyon/page/SAYFA"   to "Animasyon",
        "${mainUrl}/tur/belgesel/page/SAYFA"    to "Belgesel",
        "${mainUrl}/tur/bilimkurgu/page/SAYFA"  to "Bilimkurgu",
        "${mainUrl}/tur/biyografi/page/SAYFA"   to "Biyografi",
        "${mainUrl}/tur/dram/page/SAYFA"        to "Dram",
        "${mainUrl}/tur/drama/page/SAYFA"       to "Drama",
        "${mainUrl}/tur/fantastik/page/SAYFA"   to "Fantastik",
        "${mainUrl}/tur/gerilim/page/SAYFA"     to "Gerilim",
        "${mainUrl}/tur/gizem/page/SAYFA"       to "Gizem",
        "${mainUrl}/tur/komedi/page/SAYFA"      to "Komedi",
        "${mainUrl}/tur/korku/page/SAYFA"       to "Korku",
        "${mainUrl}/tur/macera/page/SAYFA"      to "Macera",
        "${mainUrl}/tur/muzik/page/SAYFA"       to "Müzik",
        "${mainUrl}/tur/muzikal/page/SAYFA"     to "Müzikal",
        "${mainUrl}/tur/reality-tv/page/SAYFA"  to "Reality TV",
        "${mainUrl}/tur/romantik/page/SAYFA"    to "Romantik",
        "${mainUrl}/tur/savas/page/SAYFA"       to "Savaş",
        "${mainUrl}/tur/spor/page/SAYFA"        to "Spor",
        "${mainUrl}/tur/suc/page/SAYFA"         to "Suç",
        "${mainUrl}/tur/tarih/page/SAYFA"       to "Tarih",
        "${mainUrl}/tur/western/page/SAYFA"     to "Western",
        "${mainUrl}/tur/yarisma/page/SAYFA"     to "Yarışma"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url      = request.data.replace("SAYFA", "$page")
        val document = req(url).document
        if (request.name == "Popüler Dizilerden Son Bölümler" || request.name == "Yeni Eklenen Bölümler") {
            val seenSeries = mutableSetOf<String>()
            val home = document.select("article.article-episode-card").mapNotNull { card ->
                val seriesKey = card.episodeSeriesKey() ?: return@mapNotNull null
                if (!seenSeries.add(seriesKey)) return@mapNotNull null
                card.toEpisodeCardResult()
            }
            val hasNext = document.selectFirst("div.woca-pagination a.next") != null
            return newHomePageResponse(request.name, home, hasNext)
        }
        if (request.name == "Dizi Arşivi") {
            val home = document.select("article.detailed-article").mapNotNull { it.toMainPageResult() }
            return newHomePageResponse(request.name, home)
        }
        val home = document.select("article.article-series-poster").mapNotNull {
            it.toMainPageResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.episodeSeriesKey(): String? {
        this.selectFirst("b.series-name")?.text()?.trim()?.lowercase()?.let { if (it.isNotEmpty()) return it }
        val title = this.selectFirst("a.episode-card-title")?.attr("title")?.trim().orEmpty()
        if (title.isEmpty()) return null
        return title
            .replace(Regex("""\s+\d+\.?\s*Sezon\s+\d+\.?\s*Bölüm.*$""", RegexOption.IGNORE_CASE), "")
            .trim()
            .lowercase()
            .ifEmpty { title.lowercase() }
    }

    private fun Element.toEpisodeCardResult(): SearchResponse? {
        val link = this.selectFirst("a.episode-card-title") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        val title = link.attr("title").trim()
            .ifEmpty { link.text().trim() }
            .ifEmpty { return null }
        val posterUrl = proxyPoster(
            fixUrlNull(
                this.selectFirst("img.afis, a.figure-link img, figure a img[data-src]")?.let { img ->
                    img.attr("data-src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
                        ?: img.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
                }
            )
        )
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val link = this.selectFirst("h3 a, a.poster-title, figure a[href]") ?: return null
        val title = link.text().trim().ifEmpty { link.attr("title").trim() }.ifEmpty { return null }
        val href = fixUrlNull(link.attr("href")) ?: return null
        val posterUrl = proxyPoster(fixUrlNull(this.posterSource()))
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    /**
     * Sitenin kendi araması (`/?s=` ve `admin-ajax.php?action=dwls_search`)
     * kaynakta takılıp hiç yanıt vermiyor (000/520). Bunun yerine
     * /dizi-arsivi/ sayfasındaki alfabetik diziden (~4800 dizi) yerelde arıyoruz.
     */
    private data class SeriesEntry(val title: String, val url: String)

    private val whitespaceRegex = Regex("""\s+""")
    private val combiningRegex  = Regex("""\p{M}+""")
    private val indexTtlMs      = 30 * 60 * 1000L

    @Volatile private var cachedIndex: List<SeriesEntry>? = null
    @Volatile private var cachedIndexAt = 0L

    private fun Element.toSeriesEntry(): SeriesEntry? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        val title = this.attr("title").removeSuffix(" izle").trim()
            .ifEmpty { this.text().trim() }
            .ifEmpty { return null }
        return SeriesEntry(title, href)
    }

    private suspend fun seriesIndex(): List<SeriesEntry> {
        val cached = cachedIndex
        if (cached != null && System.currentTimeMillis() - cachedIndexAt < indexTtlMs) return cached

        val entries = try {
            val doc = req("${mainUrl}/dizi-arsivi/").document
            // alfabetik dizin + sayfadaki en yeni kartlar (dizin yeni eklenen dizileri her zaman içermiyor)
            val fromLists = doc.select("ul.alphabetical-category-list > li > a[href]").mapNotNull { it.toSeriesEntry() }
            val fromCards = doc.select("article.detailed-article h3 a[href]").mapNotNull { it.toSeriesEntry() }
            (fromLists + fromCards).distinctBy { it.url }
        } catch (e: Exception) {
            emptyList()
        }

        if (entries.isNotEmpty()) {
            cachedIndex   = entries
            cachedIndexAt = System.currentTimeMillis()
        }
        return entries.ifEmpty { cached ?: emptyList() }
    }

    /** Türkçe/aksanlı karakterleri katlar: "Tanıyorum" -> "taniyorum", "círculo" -> "circulo" */
    private fun foldText(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(combiningRegex, "")
            .replace('ı', 'i')

    private fun filterSeries(index: List<SeriesEntry>, query: String): List<SearchResponse> {
        val needle = foldText(query.trim().replace(whitespaceRegex, " "))
        if (needle.isEmpty() || index.isEmpty()) return emptyList()

        return index
            .filter { foldText(it.title).contains(needle) }
            .sortedByDescending { foldText(it.title).startsWith(needle) }
            .take(50)
            .map { newTvSeriesSearchResponse(it.title, it.url, TvType.TvSeries) }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Site araması afişli kartlar döndürür ama sadece ASCII sorgularda çalışır;
        // yerel dizin her sorguda (Türkçe dahil) çalışır ama afişi yoktur.
        val remote = try { searchOnSite(query) } catch (e: Exception) { emptyList() }
        val local  = try { filterSeries(seriesIndex(), query) } catch (e: Exception) { emptyList() }

        if (remote.isEmpty()) return local
        if (local.isEmpty())  return remote

        val merged = LinkedHashMap<String, SearchResponse>()
        remote.forEach { merged.putIfAbsent(it.url, it) }
        local.forEach  { merged.putIfAbsent(it.url, it) }
        return merged.values.take(50)
    }

    /** Site'nin /search/<q>/ uç noktası — sadece ASCII sorgular (CF non-ASCII istekte 403 dönüyor). */
    private suspend fun searchOnSite(query: String): List<SearchResponse> {
        val q = query.trim().replace(whitespaceRegex, "+")
        if (q.isEmpty() || q.any { it.code > 127 }) return emptyList()

        return req("${mainUrl}/search/$q/").document
            .select("article.detailed-article")
            .mapNotNull { it.toMainPageResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? = loadInternal(url, allowSeriesRedirect = true)

    private suspend fun loadInternal(url: String, allowSeriesRedirect: Boolean): LoadResponse? {
        val document = req(url).document

        // Bölüm sayfasındaysak (Yeni Eklenen Bölümler) dizi sayfasına yönlendir
        if (document.selectFirst("div#seasons-list") == null) {
            if (allowSeriesRedirect) {
                val seriesUrl = fixUrlNull(document.selectFirst("a.archive-title")?.attr("href"))
                    ?: fixUrlNull(document.selectFirst("#archive-box a.figure")?.attr("href"))
                if (seriesUrl != null && seriesUrl != url) {
                    return loadInternal(seriesUrl, allowSeriesRedirect = false)
                }
            }
            return loadEpisodeAsSeries(url, document)
        }

        val title       = document.selectFirst("div.tv-overview h1 a")?.text()?.trim() ?: return null
        val poster      = proxyPoster(fixUrlNull(document.selectFirst("div.tv-overview figure img")?.let { img ->
            img.attr("data-src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
                ?: img.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
        }))
        val description = document.selectFirst("div.tv-story p")?.text()?.trim()
        val year        = document.selectFirst("a[href*='/yil/']")?.text()?.trim()?.toIntOrNull()
        val tags        = document.select("a[href*='/tur/']").map { it.text() }
        val actors      = document.select("a[href*='/oyuncu/']").map { Actor(it.text()) }
        val trailer     = document.selectFirst("div.tv-overview iframe")?.attr("src")

        // Bölüm kartlarının afişleri (background-image) sadece dizi sayfasında var
        val episodePosters = document.select("article.grid-box").mapNotNull { box ->
            val boxHref = fixUrlNull(box.selectFirst("div.post-title a")?.attr("href")) ?: return@mapNotNull null
            val boxImg  = fixUrlNull(box.backgroundImageUrl()) ?: return@mapNotNull null
            boxHref to proxyPoster(boxImg)
        }.toMap()

        val episodeList = mutableListOf<Episode>()
        document.select("div#seasons-list a").forEach {
            val epUrl = fixUrlNull(it.attr("href")) ?: return@forEach
            val epDoc = req(epUrl).document

            epDoc.select("article.grid-box").forEach ep@{ epElem ->
                val epTitle   = epElem.selectFirst("div.post-title a")?.text()?.trim() ?: return@ep
                val epHref    = fixUrlNull(epElem.selectFirst("div.post-title a")?.attr("href")) ?: return@ep
                val epSeason  = Regex("""(\d+)\. ?Sezon""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epEpisode = Regex("""(\d+)\. ?Bölüm""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()

                episodeList.add(newEpisode(epHref) {
                    this.name      = epTitle
                    this.season    = epSeason
                    this.episode   = epEpisode
                    this.posterUrl = episodePosters[epHref]
                })
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeList) {
            this.posterUrl = poster
            this.plot      = description
            this.year      = year
            this.tags      = tags
            addActors(actors)
            addTrailer(trailer)
        }
    }

    private suspend fun loadEpisodeAsSeries(url: String, document: org.jsoup.nodes.Document): LoadResponse? {
        val seriesName = document.selectFirst("span.tv-title-archive span[itemprop=name]")?.text()?.trim()
            ?: document.selectFirst("h1 span.tv-title-archive")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" 1.")?.trim()
            ?: return null
        val episodeTitle = document.selectFirst("span.tv-title-episode")?.text()?.trim()
        val title = if (episodeTitle != null) "$seriesName $episodeTitle" else seriesName
        val poster = proxyPoster(fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content")))
        val description = fixUrlNull(document.selectFirst("meta[property=og:description]")?.attr("content"))
        val season = Regex("""(\d+)\. ?Sezon""").find(episodeTitle ?: "")?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val episode = Regex("""(\d+)\. ?Bölüm""").find(episodeTitle ?: "")?.groupValues?.get(1)?.toIntOrNull()

        val episodes = listOf(newEpisode(url) {
            this.name = episodeTitle ?: title
            this.season = season
            this.episode = episode
            this.posterUrl = poster
        })

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    private fun extractSubtitles(source: String, subtitleCallback: (SubtitleFile) -> Unit) {
        val subUrls = mutableSetOf<String>()
        Regex(""""file"\s*:\s*"([^"]+)"[^}]*"label"\s*:\s*"([^"]+)"""").findAll(source).forEach {
            val file = it.groupValues[1].replace("\\/", "/").replace("\\", "")
            val label = it.groupValues[2]
                .replace("\\u0131", "ı").replace("\\u0130", "İ")
                .replace("\\u00fc", "ü").replace("\\u00e7", "ç")
            if (file !in subUrls) {
                subUrls.add(file)
                @Suppress("DEPRECATION")
                subtitleCallback.invoke(SubtitleFile(label, fixUrl(file)))
            }
        }
        Regex("""tracks\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(source)?.groupValues?.getOrNull(1)?.let { tracks ->
            Regex("""file\s*:\s*["']([^"']+)[^}]*?label\s*:\s*["']([^"']+)""").findAll(tracks).forEach {
                val file = it.groupValues[1].replace("\\/", "/").replace("\\", "")
                val label = it.groupValues[2]
                if (file !in subUrls) {
                    subUrls.add(file)
                    @Suppress("DEPRECATION")
                    subtitleCallback.invoke(SubtitleFile(label, fixUrl(file)))
                }
            }
        }
    }

    private suspend fun iframeDecode(data: String, iframe: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        @Suppress("NAME_SHADOWING") var iframe = iframe

        if (iframe.contains("/player/king/king.php")) {
            iframe = iframe.replace("king.php?v=", "king.php?wmode=opaque&v=")
            val subDoc = req(iframe, ref = data).document
            val subFrame = subDoc.selectFirst("div#Player iframe")?.attr("src") ?: return false

            val iDoc = req(subFrame, ref = "${mainUrl}/").text

            extractSubtitles(iDoc, subtitleCallback)

            val cryptData = Regex("CryptoJS\\.AES\\.decrypt\\(\"(.*)\",\"").find(iDoc)?.groupValues?.get(1) ?: return false
            val cryptPass = Regex("\",\"(.*)\"\\);").find(iDoc)?.groupValues?.get(1) ?: return false
            val decryptedData = CryptoJS.decrypt(cryptPass, cryptData)
            val decryptedDoc = Jsoup.parse(decryptedData)

            extractSubtitles(decryptedDoc.html(), subtitleCallback)

            val vidUrl = Regex("file: '(.*)',").find(decryptedDoc.html())?.groupValues?.get(1) ?: return false

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = vidUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    headers = mapOf("Referer" to vidUrl)
                    quality = getQualityFromName("4k")
                }
            )

        } else if (iframe.contains("/player/moly/moly.php")) {
            iframe = iframe.replace("moly.php?h=", "moly.php?wmode=opaque&h=")
            var subDoc = req(iframe, ref = data).document

            val atobData = Regex("""unescape\("(.*)"\)""").find(subDoc.html())?.groupValues?.get(1)
            if (atobData != null) {
                val decodedAtob = atobData.decodeUri()
                val strAtob = String(Base64.decode(decodedAtob, Base64.DEFAULT), Charsets.UTF_8)
                subDoc = Jsoup.parse(strAtob)
            }

            extractSubtitles(subDoc.html(), subtitleCallback)

            val subFrame = subDoc.selectFirst("div#Player iframe")?.attr("src") ?: return false

            loadExtractor(subFrame, "${mainUrl}/", subtitleCallback, callback)

        } else if (iframe.contains("/player/haydi.php")) {
            iframe = iframe.replace("haydi.php?v=", "haydi.php?wmode=opaque&v=")
            var subDoc = req(iframe, ref = data).document

            val atobData = Regex("""unescape\("(.*)"\)""").find(subDoc.html())?.groupValues?.get(1)
            if (atobData != null) {
                val decodedAtob = atobData.decodeUri()
                val strAtob = String(Base64.decode(decodedAtob, Base64.DEFAULT), Charsets.UTF_8)
                subDoc = Jsoup.parse(strAtob)
            }

            extractSubtitles(subDoc.html(), subtitleCallback)

            val subFrame = subDoc.selectFirst("div#Player iframe")?.attr("src") ?: return false

            loadExtractor(subFrame, "${mainUrl}/", subtitleCallback, callback)
        }

        return true
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("DZBX", "data » $data")
        val document = req(data).document
        var iframe = document.selectFirst("div#video-area iframe")?.attr("src") ?: return false
        Log.d("DZBX", "iframe » $iframe")

        iframeDecode(data, iframe, subtitleCallback, callback)

        document.select("div.video-toolbar option[value]").forEach {
            val altLink = it.attr("value")
            val subDoc = req(altLink).document
            iframe = subDoc.selectFirst("div#video-area iframe")?.attr("src") ?: return false
            Log.d("DZBX", "iframe » $iframe")

            iframeDecode(data, iframe, subtitleCallback, callback)
        }

        return true
    }
}

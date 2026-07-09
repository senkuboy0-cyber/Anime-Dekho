package com.Toonstream

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.runBlocking
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document

class Toonstream : MainAPI() {
    override var mainUrl: String = runBlocking {
        ToonstreamProvider.getDomains()?.Toonstream ?: "https://toon-stream.site"
    }
    override var name                 = "Toonstream"
    override val hasMainPage          = true
    override var lang                 = "hi"
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.Anime, TvType.Cartoon)

    // -----------------------------------------------------------
    // HOME PAGE TABS
    // Fresh Drop is scraped from /home/ (other tabs are paged
    // category archives using ?paged=N).
    // -----------------------------------------------------------
    override val mainPage = mainPageOf(
        "fresh-drop"                              to "Fresh Drop",
        "series"                                  to "Series",
        "movies"                                  to "Movies",
        "category/cartoon"                        to "Cartoon",
        "category/anime"                          to "Animes",
        "category/anime-series"                   to "Anime Series",
        "category/anime-movies"                   to "Anime Movies",
        "category/language/hindi-language"        to "Hindi",
        "category/animation-&-cartoon-series"     to "Animation & Cartoon Series",
        "category/animation-&-cartoon-movie"      to "Animation & Cartoon Movie"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Fresh Drop -> home page widget
        if (request.data == "fresh-drop") {
            val items = fetchFreshDrop()
            return newHomePageResponse(
                list = HomePageList(
                    name = request.name,
                    list = items,
                    isHorizontalImages = true
                ),
                hasNext = false
            )
        }

        // Movies special case: only /category/movies works (not /movies)
        val path = if (request.data == "movies") "category/movies" else request.data

        // Pagination: ?paged=N (NOT /page/N/)
        val url = if (page == 1) {
            "$mainUrl/$path/"
        } else {
            "$mainUrl/$path/?paged=$page"
        }

        val document = app.get(url).document
        val home = document.select("#movies-a ul > li").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = home.isNotEmpty()
        )
    }

    // -----------------------------------------------------------
    // Fresh Drop: scraped from the home page widget
    // -----------------------------------------------------------
    private suspend fun fetchFreshDrop(): List<SearchResponse> {
        val document = app.get("$mainUrl/home/").document

        val header = document.select("h3.section-title")
            .firstOrNull { it.text().contains("Fresh Drop", ignoreCase = true) }
            ?: return emptyList()

        val section = header.parents().firstOrNull {
            it.select("article.post.dfx").isNotEmpty()
        } ?: return emptyList()

        return section.select("article.post.dfx").mapNotNull { el ->
            val title = el.selectFirst("h2.entry-title")?.text()?.trim()
                ?.replace("Watch Online", "")?.trim() ?: return@mapNotNull null
            val href  = el.selectFirst("a.lnk-blk")?.attr("href")?.let { fixUrl(it) }
                ?: return@mapNotNull null
            val posterRaw = el.selectFirst("img")?.attr("src")
            val poster = if (posterRaw.isNullOrEmpty()) null
                else if (posterRaw.startsWith("http")) posterRaw
                else "https:$posterRaw"
            val rating = el.selectFirst("span.vote")?.text()
                ?.replace("TMDB", "")?.trim()?.toDoubleOrNull()

            newMovieSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.score = Score.from10(rating)
            }
        }
    }

    // -----------------------------------------------------------
    // Parses a single <li> post from a category / series / movies page
    // -----------------------------------------------------------
    private fun Element.toSearchResult(): SearchResponse {
        val title = this.select("article > header > h2").text().trim().replace("Watch Online", "")
        val href  = fixUrl(this.select("article > a").attr("href"))
        val posterRaw = this.select("article > div.post-thumbnail > figure > img").attr("src")
        val poster = if (posterRaw.startsWith("http")) posterRaw else "https:$posterRaw"

        // Detect type from URL or class
        val tvType = when {
            href.contains("/series/") -> TvType.TvSeries
            href.contains("/movies/") -> TvType.Movie
            else -> TvType.Movie
        }

        return newMovieSearchResponse(title, href, tvType) {
            this.posterUrl = poster
        }
    }

    private fun Element.toSearch(): SearchResponse = this.toSearchResult()

    // -----------------------------------------------------------
    // SEARCH
    // -----------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()

        for (i in 1..3) {
            val document = app.get("${mainUrl}/page/$i/?s=$query").document
            val results = document.select("#movies-a ul > li").mapNotNull { it.toSearch() }

            if (!searchResponse.containsAll(results)) {
                searchResponse.addAll(results)
            } else {
                break
            }
            if (results.isEmpty()) break
        }
        return searchResponse
    }

    // -----------------------------------------------------------
    // LOAD
    // Detects series vs movie and dispatches accordingly.
    // Series pages use /series/<slug>/season/<n> AJAX endpoints.
    // -----------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("header.entry-header > h1")?.text()?.trim()
            .toString().replace("Watch Online", "")
        val posterRaw = document.select("div.bghd > img").attr("src")
        val poster = if (posterRaw.startsWith("http")) posterRaw else "https:$posterRaw"
        val description = document.selectFirst("div.description > p")?.text()?.trim()

        return if (url.contains("/series/")) {
            loadSeries(url, document, title, poster, description)
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    // -----------------------------------------------------------
    // SERIES HANDLING
    // 1) Read season buttons to find all seasons
    // 2) For each season, request /series/<slug>/season/<n>
    // 3) Parse episode list from that page
    // -----------------------------------------------------------
    private suspend fun loadSeries(
        url: String,
        document: Document,
        title: String,
        poster: String,
        description: String?
    ): LoadResponse {
        val episodes = mutableListOf<Episode>()

        // Find every season number
        val seasonNumbers = document.select("a.season-btn").mapNotNull { el ->
            el.attr("data-season").toIntOrNull()
        }.distinct().sorted()

        // Try AJAX endpoint first (action_select_season)
        // Fallback: season-specific pages
        for (season in seasonNumbers) {
            val seasonDoc = try {
                app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "action_select_season",
                        "season" to season.toString(),
                        "post"   to (document.selectFirst("a.season-btn[data-season='$season']")
                            ?.attr("data-post") ?: "")
                    ),
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                ).document
            } catch (e: Exception) {
                org.jsoup.Jsoup.parse("")
            }

            // If AJAX returned nothing useful, try direct URL
            val finalDoc = if (seasonDoc.select("article").isEmpty()) {
                try {
                    app.get("$url/season/$season").document
                } catch (e: Exception) {
                    seasonDoc
                }
            } else {
                seasonDoc
            }

            finalDoc.select("article.post.episodes, article.post").forEach { ep ->
                val epHref = ep.selectFirst("a.lnk-blk")?.attr("href")
                    ?: ep.selectFirst("a")?.attr("href")
                    ?: return@forEach
                val epPoster = ep.selectFirst("img")?.attr("src")
                    ?.let { if (it.startsWith("http")) it else "https:$it" }
                val epName = ep.selectFirst("h5.entry-title1, h2.entry-title, h3.entry-title")?.text()?.trim()
                    ?: "Episode"

                episodes.add(
                    newEpisode(fixUrl(epHref)) {
                        this.name = epName
                        this.posterUrl = epPoster
                        this.season = season
                    }
                )
            }
        }

        // If AJAX and direct URLs both failed, fall back to the
        // episodes already rendered on the first series page
        if (episodes.isEmpty()) {
            document.select("#episode_by_temp article.post").forEach { ep ->
                val epHref = ep.selectFirst("a.lnk-blk")?.attr("href")
                    ?: ep.selectFirst("a")?.attr("href")
                    ?: return@forEach
                val epPoster = ep.selectFirst("img")?.attr("src")
                    ?.let { if (it.startsWith("http")) it else "https:$it" }
                val epName = ep.selectFirst("h5.entry-title1")?.text()?.trim()
                    ?: "Episode"
                val numEpi = ep.selectFirst("span.num-epi")?.text()?.trim()
                val epSeason = numEpi?.substringBefore("x")?.toIntOrNull() ?: 1

                episodes.add(
                    newEpisode(fixUrl(epHref)) {
                        this.name = epName
                        this.posterUrl = epPoster
                        this.season = epSeason
                    }
                )
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    // -----------------------------------------------------------
    // LOAD LINKS
    // -----------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        document.select("#aa-options > div > iframe").forEach {
            val serverlink = it.attr("data-src")
            if (serverlink.isNotEmpty()) {
                val truelink = try {
                    app.get(serverlink).document.selectFirst("iframe")?.attr("src") ?: ""
                } catch (e: Exception) { "" }
                if (truelink.isNotEmpty()) {
                    loadExtractor(truelink, subtitleCallback, callback)
                }
            }
        }
        return true
    }
}

class Zephyrflick : AWSStream() {
    override val name = "Zephyrflick"
    override val mainUrl = "https://play.zephyrflick.top"
    override val requiresReferer = true
}

open class AWSStream : ExtractorApi() {
    override val name = "AWSStream"
    override val mainUrl = "https://z.awstream.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val extractedHash = url.substringAfterLast("/")
        val doc = app.get(url).document
        val m3u8Url = "$mainUrl/player/index.php?data=$extractedHash&do=getVideo"
        val header = mapOf("x-requested-with" to "XMLHttpRequest")
        val formdata = mapOf("hash" to extractedHash, "r" to mainUrl)
        val response = app.post(m3u8Url, headers = header, data = formdata).parsedSafe<Response>()
        response?.videoSource?.let { m3u8 ->
            callback(
                newExtractorLink(
                    name,
                    name,
                    url = m3u8,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = ""
                    this.quality = Qualities.P1080.value
                }
            )
            val extractedPack = doc.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data().orEmpty()
            JsUnpacker(extractedPack).unpack()?.let { unpacked ->
                Regex("""kind":\s*"captions"\s*,\s*"file":\s*"(https.*?\.srt)""")
                    .find(unpacked)
                    ?.groupValues
                    ?.get(1)
                    ?.let { subtitleUrl ->
                        subtitleCallback.invoke(SubtitleFile("English", subtitleUrl))
                    }
            }
        }
    }

    data class Response(
        val hls: Boolean,
        val videoImage: String,
        val videoSource: String,
        val securedLink: String,
        val downloadLinks: List<Any?>,
        val attachmentLinks: List<Any?>,
        val ck: String,
    )
}

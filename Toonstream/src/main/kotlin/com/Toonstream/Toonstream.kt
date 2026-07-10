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

    // Fresh Drop first, then directly Anime Series — removed Series/Movies/Cartoon/Animes
    override val mainPage = mainPageOf(
        "fresh-drop"                              to "Fresh Drop",
        "category/anime-series"                   to "Anime Series",
        "category/anime-movies"                   to "Anime Movies",
        "category/language/hindi-language"        to "Hindi",
        "category/animation-&-cartoon-series"     to "Animation & Cartoon Series",
        "category/animation-&-cartoon-movie"      to "Animation & Cartoon Movie"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == "fresh-drop") {
            val items = fetchFreshDrop()
            return newHomePageResponse(
                list = HomePageList(name = request.name, list = items, isHorizontalImages = true),
                hasNext = false
            )
        }

        val path = request.data
        // FIX: site now uses ?page=N for pagination — ?paged=N always returns page 1
        val url = if (page == 1) "$mainUrl/$path/" else "$mainUrl/$path/?page=$page"

        val document = app.get(url).document
        val home = document.select("#movies-a ul > li").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(name = request.name, list = home, isHorizontalImages = false),
            hasNext = home.isNotEmpty()
        )
    }

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

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("article > header > h2, article h2.entry-title")
            ?.text()?.trim()?.replace("Watch Online", "")
            ?.ifBlank { return null } ?: return null
        val href  = fixUrl(
            this.selectFirst("article > a.lnk-blk, article a.lnk-blk")
                ?.attr("href") ?: return null
        )
        val posterRaw = this.selectFirst("article img")?.attr("src") ?: ""
        val poster = when {
            posterRaw.startsWith("http") -> posterRaw
            posterRaw.startsWith("//")   -> "https:$posterRaw"
            posterRaw.isNotEmpty()       -> posterRaw
            else                         -> null
        }
        val tvType = when {
            href.contains("/series/") -> TvType.TvSeries
            href.contains("/movies/") -> TvType.Movie
            else                      -> TvType.Movie
        }
        return newMovieSearchResponse(title, href, tvType) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        for (i in 1..3) {
            val doc = app.get("$mainUrl/page/$i/?s=$query").document
            val page = doc.select("#movies-a ul > li").mapNotNull { it.toSearchResult() }
            if (page.isEmpty() || results.containsAll(page)) break
            results.addAll(page)
        }
        return results
    }

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

    private suspend fun loadSeries(
        url: String,
        document: Document,
        title: String,
        poster: String,
        description: String?
    ): LoadResponse {
        val episodes = mutableListOf<Episode>()

        val seasonNumbers = document.select("a.season-btn").mapNotNull { el ->
            el.attr("data-season").toIntOrNull()
        }.distinct().sorted()

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

            val finalDoc = if (seasonDoc.select("article").isEmpty()) {
                try { app.get("$url/season/$season").document }
                catch (e: Exception) { seasonDoc }
            } else seasonDoc

            finalDoc.select("article.post.episodes, article.post").forEach { ep ->
                val epHref = ep.selectFirst("a.lnk-blk, a")?.attr("href") ?: return@forEach
                val epPoster = ep.selectFirst("img")?.attr("src")
                    ?.let { if (it.startsWith("http")) it else "https:$it" }
                val epName = ep.selectFirst("h5.entry-title1, h2.entry-title, h3.entry-title")
                    ?.text()?.trim() ?: "Episode"
                episodes.add(newEpisode(fixUrl(epHref)) {
                    this.name     = epName
                    this.posterUrl = epPoster
                    this.season   = season
                })
            }
        }

        if (episodes.isEmpty()) {
            document.select("#episode_by_temp article.post").forEach { ep ->
                val epHref = ep.selectFirst("a.lnk-blk, a")?.attr("href") ?: return@forEach
                val epPoster = ep.selectFirst("img")?.attr("src")
                    ?.let { if (it.startsWith("http")) it else "https:$it" }
                val epName = ep.selectFirst("h5.entry-title1")?.text()?.trim() ?: "Episode"
                val numEpi = ep.selectFirst("span.num-epi")?.text()?.trim()
                val epSeason = numEpi?.substringBefore("x")?.toIntOrNull() ?: 1
                episodes.add(newEpisode(fixUrl(epHref)) {
                    this.name     = epName
                    this.posterUrl = epPoster
                    this.season   = epSeason
                })
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    // ─────────────────────────────────────────────────────────────
    // loadLinks — all bugs fixed + Zephyrflick plays first
    // ─────────────────────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // Collect true links from all servers first, then sort by priority
        data class ServerInfo(val truelink: String, val referer: String, val priority: Int)

        val servers = document.select("#aa-options > div > iframe").mapNotNull { iframe ->
            // FIX 1: first iframe uses src, the rest use data-src
            val rawSrc = iframe.attr("data-src").ifEmpty { iframe.attr("src") }
            if (rawSrc.isEmpty()) return@mapNotNull null

            // FIX 2: relative URL (/embed/xxx) -> absolute URL
            val serverlink = if (rawSrc.startsWith("http")) rawSrc else "$mainUrl$rawSrc"

            // Fetch the embed page and extract the actual provider iframe src
            val truelink = try {
                app.get(serverlink, referer = mainUrl)
                    .document
                    .selectFirst(".Video iframe, div.Video iframe, iframe[src]")
                    ?.attr("src") ?: ""
            } catch (e: Exception) { "" }

            if (truelink.isEmpty()) return@mapNotNull null

            // FIX 3: priority order — as-cdn21.top (Zephyrflick) always first
            val priority = when {
                truelink.contains("as-cdn21.top")    -> 0  // Zephyrflick 1080p — FIRST
                truelink.contains("emturbovid.com")  -> 1  // EmTurboVid 1080p
                truelink.contains("gdmirrorbot.nl")  -> 2  // GDMirrorbot HD/FHD
                truelink.contains("rubystm.com")     -> 3  // Streamruby
                truelink.contains("vidmoly.net")     -> 4  // VidMoly
                truelink.contains("abyssplayer.com") -> 5  // AbyssPlayer
                truelink.contains("cloudy.upns.one") -> 6  // VidStack (CDN token expiry known issue)
                else                                 -> 7
            }
            ServerInfo(truelink, serverlink, priority)
        }

        // Sort by priority and fire callbacks in order
        // as-cdn21.top (priority=0) fires first -> Zephyrflick appears first in sources list
        servers.sortedBy { it.priority }.forEach { server ->
            loadExtractor(server.truelink, server.referer, subtitleCallback, callback)
        }
        return true
    }
}

// ─── AWSStream / Zephyrflick ──────────────────────────────────
class Zephyrflick : AWSStream() {
    override val name    = "Zephyrflick"
    override val mainUrl = "https://play.zephyrflick.top"
    override val requiresReferer = true
}

open class AWSStream : ExtractorApi() {
    override val name    = "AWSStream"
    override val mainUrl = "https://z.awstream.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val extractedHash = url.substringAfterLast("/")
        val header   = mapOf("x-requested-with" to "XMLHttpRequest")
        val formdata = mapOf("hash" to extractedHash, "r" to mainUrl)
        val apiUrl   = "$mainUrl/player/index.php?data=$extractedHash&do=getVideo"
        val response = app.post(apiUrl, headers = header, data = formdata)
            .parsedSafe<Response>()

        response?.videoSource?.let { m3u8 ->
            callback(
                newExtractorLink(name, name, url = m3u8, type = ExtractorLinkType.M3U8) {
                    this.referer = ""
                    this.quality = Qualities.P1080.value
                }
            )
            // Subtitle check
            val doc = app.get(url).document
            val packed = doc.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data()
                .orEmpty()
            JsUnpacker(packed).unpack()?.let { unpacked ->
                Regex("""kind":\s*"captions"\s*,\s*"file":\s*"(https.*?\.srt)""")
                    .find(unpacked)?.groupValues?.get(1)?.let { srtUrl ->
                        subtitleCallback(SubtitleFile("English", srtUrl))
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

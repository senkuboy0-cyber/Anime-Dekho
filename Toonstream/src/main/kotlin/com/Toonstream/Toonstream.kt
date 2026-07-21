package com.Toonstream

import com.fasterxml.jackson.annotation.JsonProperty
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
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import java.net.URLEncoder

data class TmdbImages(
    @JsonProperty("logos") val logos: List<TmdbImage>? = null,
    @JsonProperty("backdrops") val backdrops: List<TmdbImage>? = null
)
data class TmdbImage(
    @JsonProperty("file_path") val filePath: String? = null,
    @JsonProperty("iso_639_1") val lang: String?     = null
)
data class TmdbFind(
    @JsonProperty("movie_results") val movies: List<TmdbResult>? = null,
    @JsonProperty("tv_results")    val tvShows: List<TmdbResult>? = null
)
data class TmdbResult(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("media_type") val mediaType: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null
)
data class TmdbSearch(
    @JsonProperty("results") val results: List<TmdbResult>? = null
)

data class ServerInfo(val truelink: String, val referer: String, val priority: Int)

class Toonstream : MainAPI() {
    override var mainUrl              = "https://toon-stream.site"
    override var name                 = "Toonstream"
    override val hasMainPage          = true
    override var lang                 = "hi"
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.Anime, TvType.Cartoon)

    private val TMDB_API = "https://api.themoviedb.org/3"
    private val TMDB_KEY = "1865f43a0549ca50d341dd9ab8b29f49"
    private val TMDB_IMG = "https://image.tmdb.org/t/p/original"

    private fun cleanTitleText(title: String): String {
        var clean = title

        clean = clean.replace(Regex("(?i)Watch Online"), "")
        
        // Safely extract text before brackets
        if (clean.contains("[")) clean = clean.substringBefore("[")
        if (clean.contains("(")) clean = clean.substringBefore("(")
        
        // Remove resolution or episode patterns like 1080x720 or 12x04
        clean = clean.replace("(?i)\\s+\\d+[x×]\\d+.*".toRegex(), "")
        clean = clean.replace("×", "x")
        
        clean = clean.replace("(?i)\\s+Episode\\s+\\d+.*".toRegex(), "")
        clean = clean.replace("(?i)\\s+Season\\s+\\d+.*".toRegex(), "")
        
        // Safely remove dub/audio info
        clean = clean.replace(Regex("(?i)\\s*(hindi dub|english dub|dual audio|multi audio|fan dub|fandub|eng-jap).*"), "")
        
        // Ultimate safe filter: keeps ONLY letters, numbers, spaces, and basic punctuation
        // This guarantees NO invisible or weird formatting characters survive.
        val sb = java.lang.StringBuilder()
        for (c in clean) {
            if (c.isLetterOrDigit() || c.isWhitespace() || c == '-' || c == '\'' || c == ':') {
                sb.append(c)
            }
        }
        
        // Clean up multiple spaces
        clean = sb.toString().replace("\\s+".toRegex(), " ")
        return clean.trim()
    }

    private fun encodeUri(text: String): String {
        return try {
            URLEncoder.encode(text, "UTF-8")
        } catch (e: Exception) {
            text.replace(" ", "+")
        }
    }

    private fun normalizeTitle(s: String?): String {
        return s?.replace(Regex("[^a-zA-Z0-9]"), "")?.lowercase() ?: ""
    }

    private fun getResultYear(result: TmdbResult): Int? {
        return (result.releaseDate ?: result.firstAirDate)
            ?.substringBefore("-")
            ?.toIntOrNull()
    }

    private fun yearMatches(tmdbYear: Int?, siteYear: Int?): Boolean {
        if (siteYear == null || tmdbYear == null) return true
        return Math.abs(tmdbYear - siteYear) <= 1
    }

    private fun pickBestResult(candidates: List<TmdbResult>, siteYear: Int?): TmdbResult? {
        if (candidates.isEmpty()) return null
        if (siteYear == null || candidates.size == 1) return candidates.first()
        return candidates.firstOrNull { yearMatches(getResultYear(it), siteYear) }
            ?: candidates.first()
    }

    private suspend fun fetchTmdbAssets(document: Document, title: String, isSeries: Boolean, year: Int?): List<String?> {
        return try {
            var tmdbId: Int? = null
            var actualMediaType = if (isSeries) "tv" else "movie"

            // Encode the 100% clean title
            val safeTitle = encodeUri(title)
            val searchRes = app.get("$TMDB_API/search/multi?api_key=$TMDB_KEY&query=$safeTitle&language=en-US")
                .parsedSafe<TmdbSearch>()

            val validResults = searchRes?.results?.filter { it.mediaType == "movie" || it.mediaType == "tv" }
            val normTitle = normalizeTitle(title)

            // 1. Strict exact match logic
            val exactCandidates = validResults?.filter { result ->
                val tmdbTitleNorm = normalizeTitle(result.title)
                val tmdbNameNorm = normalizeTitle(result.name)
                tmdbTitleNorm == normTitle || tmdbNameNorm == normTitle
            } ?: emptyList()

            val exactMatch = pickBestResult(exactCandidates, year)

            if (exactMatch != null) {
                tmdbId = exactMatch.id
                actualMediaType = exactMatch.mediaType ?: actualMediaType
            } else {
                // 2. Fallback to startsWith ONLY if exact match fails
                val startsWithCandidates = if (normTitle.length >= 4) {
                    validResults?.filter { result ->
                        val tmdbNorm = normalizeTitle(result.title ?: result.name)
                        tmdbNorm.startsWith(normTitle) || normTitle.startsWith(tmdbNorm)
                    } ?: emptyList()
                } else emptyList()

                val startsWithMatch = pickBestResult(startsWithCandidates, year)

                if (startsWithMatch != null) {
                    tmdbId = startsWithMatch.id
                    actualMediaType = startsWithMatch.mediaType ?: actualMediaType
                } else {
                    // 3. Fallback to IMDb ID
                    val imdbId = document.select("a[href*='imdb.com/title']").attr("href")
                        .substringAfter("title/").substringBefore("/")
                        .takeIf { it.startsWith("tt") }

                    if (imdbId != null) {
                        app.get("$TMDB_API/find/$imdbId?api_key=$TMDB_KEY&external_source=imdb_id")
                            .parsedSafe<TmdbFind>()
                            ?.let { findRes ->
                                val tvId    = findRes.tvShows?.firstOrNull()?.id
                                val movieId = findRes.movies?.firstOrNull()?.id

                                if (isSeries) {
                                    if (tvId != null)         { tmdbId = tvId;    actualMediaType = "tv"    }
                                    else if (movieId != null) { tmdbId = movieId; actualMediaType = "movie" }
                                } else {
                                    if (movieId != null)      { tmdbId = movieId; actualMediaType = "movie" }
                                    else if (tvId != null)    { tmdbId = tvId;    actualMediaType = "tv"    }
                                }
                            }
                    }
                }
            }

            if (tmdbId == null) return listOf(null, null)

            // Added 'ja' to ensure it fetches Japanese logos if English ones are missing
            val images = app.get(
                "$TMDB_API/$actualMediaType/$tmdbId/images?api_key=$TMDB_KEY&include_image_language=en,null,ja"
            ).parsedSafe<TmdbImages>()

            val logo = images?.logos?.firstOrNull { it.lang == "en" }
                ?: images?.logos?.firstOrNull { it.lang == null }
                ?: images?.logos?.firstOrNull { it.lang == "ja" }
                ?: images?.logos?.firstOrNull()

            val backdrop = images?.backdrops?.firstOrNull { it.lang == null }
                ?: images?.backdrops?.firstOrNull { it.lang == "en" }
                ?: images?.backdrops?.firstOrNull()

            val logoUrl     = logo?.filePath?.let { "$TMDB_IMG$it" }
            val backdropUrl = backdrop?.filePath?.let { "$TMDB_IMG$it" }

            listOf(logoUrl, backdropUrl)

        } catch (e: Exception) {
            listOf(null, null)
        }
    }

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
            val rawTitle = el.selectFirst("h2.entry-title")?.text()
                ?.replace(Regex("(?i)Watch Online"), "")?.trim() ?: return@mapNotNull null
            
            val cleanedTitle = cleanTitleText(rawTitle)
            if (cleanedTitle.isBlank()) return@mapNotNull null

            val href  = el.selectFirst("a.lnk-blk")?.attr("href")?.let { fixUrl(it) }
                ?: return@mapNotNull null
            val posterRaw = el.selectFirst("img")?.attr("src")
            val poster = if (posterRaw.isNullOrEmpty()) null
                else if (posterRaw.startsWith("http")) posterRaw
                else "https:$posterRaw"
            val rating = el.selectFirst("span.vote")?.text()
                ?.replace("TMDB", "")?.trim()?.toDoubleOrNull()

            newMovieSearchResponse(rawTitle, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.score = Score.from10(rating)
            }
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val rawTitle = this.selectFirst("article > header > h2, article h2.entry-title")
            ?.text()?.replace(Regex("(?i)Watch Online"), "")?.trim() ?: return null
            
        val cleanedTitle = cleanTitleText(rawTitle)
        if (cleanedTitle.isBlank()) return null

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
        
        return newMovieSearchResponse(rawTitle, href, tvType) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        for (i in 1..3) {
            val searchUrl = if (i == 1) {
                "$mainUrl/s?q=$query"
            } else {
                "$mainUrl/page/$i/s?q=$query"
            }

            val doc = app.get(searchUrl).document

            var page = doc.select("#movies-a ul > li").mapNotNull { it.toSearchResult() }
            if (page.isEmpty()) {
                page = doc.select("article, .result-item, .item").mapNotNull { it.toSearchResult() }
            }

            if (page.isEmpty() || results.containsAll(page)) break
            results.addAll(page)
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val document   = app.get(url).document

        val rawTitle   = document.selectFirst("header.entry-header > h1")
            ?.text()?.replace(Regex("(?i)Watch Online"), "")?.trim() ?: ""
        val cleanTitle = cleanTitleText(rawTitle)

        val posterRaw  = document.select("div.bghd > img").attr("src")
        val poster     = if (posterRaw.startsWith("http")) posterRaw else "https:$posterRaw"
        val description = document.selectFirst("div.description > p")?.text()?.trim()
        val isSeries   = url.contains("/series/")

        val year = document.selectFirst("span.year")?.text()?.trim()?.toIntOrNull()

        val tmdbAssets  = fetchTmdbAssets(document, cleanTitle, isSeries, year)
        val logoUrl     = tmdbAssets[0]
        val backdropUrl = tmdbAssets[1]

        val displayTitle = rawTitle

        return if (isSeries) {
            loadSeries(url, document, displayTitle, poster, description, logoUrl, backdropUrl, year)
        } else {
            newMovieLoadResponse(displayTitle, url, TvType.Movie, url) {
                this.posterUrl           = poster
                this.backgroundPosterUrl = backdropUrl ?: poster
                this.plot                = description
                this.year                = year
                this.logoUrl             = logoUrl
            }
        }
    }

    private suspend fun loadSeries(
        url: String,
        document: Document,
        title: String,
        poster: String,
        description: String?,
        logoUrl: String?,
        backdropUrl: String?,
        year: Int?
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
                    this.name      = epName
                    this.posterUrl = epPoster
                    this.season    = season
                })
            }
        }

        if (episodes.isEmpty()) {
            document.select("#episode_by_temp article.post").forEach { ep ->
                val epHref = ep.selectFirst("a.lnk-blk, a")?.attr("href") ?: return@forEach
                val epPoster = ep.selectFirst("img")?.attr("src")
                    ?.let { if (it.startsWith("http")) it else "https:$it" }
                val epName   = ep.selectFirst("h5.entry-title1")?.text()?.trim() ?: "Episode"
                val numEpi   = ep.selectFirst("span.num-epi")?.text()?.trim()
                val epSeason = numEpi?.substringBefore("x")?.toIntOrNull() ?: 1
                episodes.add(newEpisode(fixUrl(epHref)) {
                    this.name      = epName
                    this.posterUrl = epPoster
                    this.season    = epSeason
                })
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl           = poster
            this.backgroundPosterUrl = backdropUrl ?: poster
            this.plot                = description
            this.year                = year
            this.logoUrl             = logoUrl
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        val servers = document.select("#aa-options > div > iframe").mapNotNull { iframe ->
            val rawSrc = iframe.attr("data-src").ifEmpty { iframe.attr("src") }
            if (rawSrc.isEmpty()) return@mapNotNull null

            val serverlink = if (rawSrc.startsWith("http")) rawSrc else "$mainUrl$rawSrc"

            val truelink = try {
                app.get(serverlink, referer = mainUrl)
                    .document
                    .selectFirst(".Video iframe, div.Video iframe, iframe[src]")
                    ?.attr("src") ?: ""
            } catch (e: Exception) { "" }

            if (truelink.isEmpty()) return@mapNotNull null

            val priority = when {
                truelink.contains("as-cdn21.top")    -> 0
                truelink.contains("emturbovid.com")  -> 1
                truelink.contains("gdmirrorbot.nl")  -> 2
                truelink.contains("rubystm.com")     -> 3
                truelink.contains("vidmoly.net")     -> 4
                truelink.contains("abyssplayer.com") -> 5
                truelink.contains("cloudy.upns.one") -> 6
                else                                 -> 7
            }
            ServerInfo(truelink, serverlink, priority)
        }

        val fixedCallback: (ExtractorLink) -> Unit = { link ->
            if (link.url.substringBefore("?").endsWith(".txt")) {
                callback(
                    ExtractorLink(
                        source        = link.source,
                        name          = link.name,
                        url           = link.url,
                        referer       = link.referer,
                        quality       = link.quality,
                        type          = ExtractorLinkType.M3U8,
                        headers       = link.headers,
                        extractorData = link.extractorData
                    )
                )
            } else {
                callback(link)
            }
        }

        servers.sortedBy { it.priority }.forEach { server ->
            loadExtractor(server.truelink, server.referer, subtitleCallback, fixedCallback)
        }
        return true
    }
}

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
            val doc = app.get(url).document
            val packed = doc.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data()
                .orEmpty()
            JsUnpacker(packed).unpack()?.let { unpacked ->
                Regex("""kind":\s*"captions"\s*,\s*"file":\s*"(https.*?\.srt)"""")
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

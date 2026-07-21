package com.anime

import com.fasterxml.jackson.annotation.JsonProperty
import com.google.gson.Gson
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

// ─── TMDB Data Classes ───
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
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    @JsonProperty("overview") val overview: String? = null
)
data class TmdbSearch(
    @JsonProperty("results") val results: List<TmdbResult>? = null
)
data class TmdbSeason(
    @JsonProperty("episodes") val episodes: List<TmdbEpisode>? = null
)
data class TmdbEpisode(
    @JsonProperty("episode_number") val episodeNumber: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("still_path") val stillPath: String? = null
)
data class TmdbDetails(
    val id: Int?, 
    val type: String?, 
    val logo: String?, 
    val backdrop: String?,
    val overview: String?
)

data class SiteEpisode(
    val href: String,
    val rawName: String,
    val poster: String?,
    val season: Int?,
    var calculatedEpNum: Int = 1,
    var finalName: String = rawName,
    var finalPoster: String? = poster
)
// ────────────────────────────────────────

open class AnimeDekhoProvider : MainAPI() {
    override var mainUrl             = "https://animedekho.app"
    override var name                = "Anime Dekho"
    override val hasMainPage         = true
    override var lang                = "hi"
    override val hasDownloadSupport  = true

    override val supportedTypes = setOf(
        TvType.Cartoon,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Movie,
    )

    // ─── TMDB API Features ──────────────────────────────────────────────
    private val TMDB_API = "https://api.themoviedb.org/3"
    private val TMDB_KEY = "1865f43a0549ca50d341dd9ab8b29f49"
    private val TMDB_IMG = "https://image.tmdb.org/t/p/original"

    // ─── Safe Regex Declarations ───
    private val epRegex1 = Regex("(?i)\\s+\\d+[x×]\\d+.*")
    private val epRegex2 = Regex("(?i)\\s+Episode\\s+\\d+.*")
    private val seasonRegex = Regex("(?i)\\s+Season\\s+\\d+.*")
    private val fanDubRegex1 = Regex("(?i)\\s*fan\\s*dub.*")
    private val fanDubRegex2 = Regex("(?i)\\s*fandub.*")
    private val normalizeRegex = Regex("[^a-zA-Z0-9]")

    /**
     * Extracts year from a TmdbResult using release_date or first_air_date
     */
    private fun getResultYear(result: TmdbResult): Int? {
        return (result.releaseDate ?: result.firstAirDate)
            ?.substringBefore("-")
            ?.toIntOrNull()
    }

    /**
     * Returns true if TMDB year and site year are within +-1 tolerance.
     * If either year is unknown, returns true to avoid filtering out valid results.
     */
    private fun yearMatches(tmdbYear: Int?, siteYear: Int?): Boolean {
        if (siteYear == null || tmdbYear == null) return true
        return Math.abs(tmdbYear - siteYear) <= 1
    }

    /**
     * Picks the best result from candidates.
     * If multiple candidates exist and siteYear is known, prefers the year-matching one.
     * Falls back to first candidate if no year match found.
     */
    private fun pickBestResult(candidates: List<TmdbResult>, siteYear: Int?): TmdbResult? {
        if (candidates.isEmpty()) return null
        if (siteYear == null || candidates.size == 1) return candidates.first()
        return candidates.firstOrNull { yearMatches(getResultYear(it), siteYear) }
            ?: candidates.first()
    }

    /**
     * A helper function to deeply clean the title for TMDB searching.
     */
    private fun cleanTitleText(title: String): String {
        var clean = title.replace(Regex("Watch Online", RegexOption.IGNORE_CASE), "")

        clean = clean.replace(epRegex1, "")
        clean = clean.replace(epRegex2, "")
        clean = clean.replace(seasonRegex, "")
        clean = clean.replace(fanDubRegex1, "")
        clean = clean.replace(fanDubRegex2, "")

        clean = clean.substringBefore("(")
        clean = clean.substringBefore("[")

        return clean.trim()
    }

    /**
     * Custom crash-proof URL encoder to safely handle all special characters
     */
    private fun encodeUri(text: String): String {
        return text.replace("%", "%25")
            .replace(" ", "%20")
            .replace("#", "%23")
            .replace("&", "%26")
            .replace("?", "%3F")
            .replace("=", "%3D")
            .replace(":", "%3A")
            .replace("/", "%2F")
            .replace("'", "%27")
            .replace("\"", "%22")
            .replace(",", "%2C")
    }

    /**
     * Normalizes title for exact matching comparison by removing ALL spaces and special characters
     */
    private fun normalizeTitle(s: String?): String {
        return s?.replace(normalizeRegex, "")?.lowercase() ?: ""
    }

    /**
     * Extracted to prevent compiler crashes and dynamically clean titles
     */
    private fun extractRawTitle(title: String): String? {
        return title
            .replace(Regex("Watch Online ", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+Anime\\s*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*Movie\\s*\\(.*Dubbed.*\\).*$", RegexOption.IGNORE_CASE), "")
            .substringBefore(" Movie in Hindi")
            .substringBefore(" Series in Hindi")
            .substringBefore(" in Hindi")
            .substringBefore(" in Tamil")
            .substringBefore(" in Telugu")
            .substringBefore(" | AnimeDekho")
            .substringBefore("| AnimeDekho")
            .substringAfter("AnimeDekho - ")
            .substringAfter("AnimeDekho – ")
            .trim()
            .takeIf {
                it.isNotEmpty() &&
                it.length > 2 &&
                !it.equals("AnimeDekho", ignoreCase = true) &&
                !it.startsWith("|")
            }
    }

    /**
     * Fetches year via AJAX request if not found directly in the DOM
     */
    private suspend fun fetchYearViaAjax(movieUrl: String, pageHtml: String): Int? {
        return try {
            val nonce = Regex("\"nonce\"\\s*:\\s*\"([^\"]+)\"").find(pageHtml)?.groupValues?.get(1) ?: return null
            
            val slug = movieUrl.trimEnd('/').substringAfterLast("/")
            val searchTerm = slug.replace(Regex("-(hin|hindi|dubbed|dub|sub)$", RegexOption.IGNORE_CASE), "")
                                 .replace("-", " ")
                                 .trim()
                                 
            val type = if (movieUrl.contains("series")) "series" else "movies"

            val vars = """{"_wpsearch":"$nonce","search":"$searchTerm","type":"$type","genres":[],"years":[],"sort":1,"page":1}"""

            val response = app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                data = mapOf("action" to "action_search", "vars" to vars),
                headers = mapOf(
                    "Content-Type"     to "application/x-www-form-urlencoded",
                    "X-WP-Nonce"       to nonce,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer"          to movieUrl
                )
            ).text

            val json = parseJson<AjaxResponse>(response)
            val yearStr = Regex("<span class=\"year\">(\\d{4})</span>").find(json.html)?.groupValues?.get(1)
            yearStr?.toIntOrNull()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Fetches TMDB Details (ID, MediaType, Logo, Backdrop, Overview)
     */
    private suspend fun fetchTmdbDetails(document: Document, title: String, isSeries: Boolean, year: Int?): TmdbDetails {
        return try {
            var tmdbId: Int? = null
            var actualMediaType = if (isSeries) "tv" else "movie"
            var tmdbOverview: String? = null

            val safeTitle = encodeUri(title)

            val searchRes = app.get("$TMDB_API/search/multi?api_key=$TMDB_KEY&query=$safeTitle")
                .parsedSafe<TmdbSearch>()

            val validResults = searchRes?.results?.filter { it.mediaType == "movie" || it.mediaType == "tv" }
            val normTitle = normalizeTitle(title)

            val exactCandidates = validResults?.filter {
                normalizeTitle(it.title) == normTitle ||
                normalizeTitle(it.name) == normTitle
            } ?: emptyList()

            val exactMatch = pickBestResult(exactCandidates, year)

            if (exactMatch != null) {
                tmdbId = exactMatch.id
                actualMediaType = exactMatch.mediaType ?: actualMediaType
                tmdbOverview = exactMatch.overview
            } else {
                val startsWithCandidates = if (normTitle.length >= 6) {
                    validResults?.filter { result ->
                        val tmdbNorm = normalizeTitle(result.title ?: result.name)
                        tmdbNorm.startsWith(normTitle)
                    } ?: emptyList()
                } else emptyList()

                val startsWithMatch = pickBestResult(startsWithCandidates, year)

                if (startsWithMatch != null) {
                    tmdbId = startsWithMatch.id
                    actualMediaType = startsWithMatch.mediaType ?: actualMediaType
                    tmdbOverview = startsWithMatch.overview
                } else {
                    val imdbId = document.select("a[href*='imdb.com/title']").attr("href")
                        .substringAfter("title/").substringBefore("/")
                        .takeIf { it.startsWith("tt") }

                    if (imdbId != null) {
                        app.get("$TMDB_API/find/$imdbId?api_key=$TMDB_KEY&external_source=imdb_id")
                            .parsedSafe<TmdbFind>()
                            ?.let { findRes ->
                                val tvMatch = findRes.tvShows?.firstOrNull()
                                val movieMatch = findRes.movies?.firstOrNull()

                                if (isSeries) {
                                    if (tvMatch != null) { 
                                        tmdbId = tvMatch.id; actualMediaType = "tv"; tmdbOverview = tvMatch.overview 
                                    }
                                    else if (movieMatch != null) { 
                                        tmdbId = movieMatch.id; actualMediaType = "movie"; tmdbOverview = movieMatch.overview 
                                    }
                                } else {
                                    if (movieMatch != null) { 
                                        tmdbId = movieMatch.id; actualMediaType = "movie"; tmdbOverview = movieMatch.overview 
                                    }
                                    else if (tvMatch != null) { 
                                        tmdbId = tvMatch.id; actualMediaType = "tv"; tmdbOverview = tvMatch.overview 
                                    }
                                }
                            }
                    }
                }
            }

            if (tmdbId == null) return TmdbDetails(null, null, null, null, null)

            val images = app.get(
                "$TMDB_API/$actualMediaType/$tmdbId/images?api_key=$TMDB_KEY"
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

            TmdbDetails(tmdbId, actualMediaType, logoUrl, backdropUrl, tmdbOverview)
        } catch (e: Exception) {
            TmdbDetails(null, null, null, null, null)
        }
    }
    // ────────────────────────────────────────────────────────────────────────

    private fun mainPageJson(taxonomy: String, search: String, term: String, type: String): String {
        return "{\"taxonomy\":\"$taxonomy\",\"search\":\"$search\",\"term\":\"$term\",\"type\":\"$type\"}"
    }

    override val mainPage = mainPageOf(
        mainPageJson("none", "none", "none", "series")          to "Series",
        mainPageJson("none", "none", "none", "movie")           to "Movies",
        mainPageJson("category", "none", "anime", "none")       to "Anime",
        mainPageJson("category", "none", "cartoon", "none")     to "Cartoon",
        mainPageJson("category", "none", "hindi-dub", "none")   to "Hindi Dub",
        mainPageJson("category", "none", "tamil", "none")       to "Tamil",
        mainPageJson("category", "none", "telugu", "none")      to "Telugu"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isSeries   = request.data.contains("type\":\"series")
        val isMovie    = request.data.contains("type\":\"movie")
        val isCategory = !isSeries && !isMovie

        if (isCategory) {
            val term      = Regex("\"term\":\"([^\"]+)\"").find(request.data)?.groupValues?.get(1) ?: ""
            val pageUrl   = "$mainUrl/category/$term/"
            val pagedUrl  = if (page == 1) pageUrl else "${pageUrl}page/$page/"
            val document  = app.get(pagedUrl).document
            val home      = document.select("article").mapNotNull { it.toSearchResult() }
            val hasNextPage = document.selectFirst("a.next.page-numbers") != null
            return newHomePageResponse(request.name, home, hasNextPage)
        }

        val pageUrl = if (isSeries) "$mainUrl/series-hindi/" else "$mainUrl/movie-hindi/"

        if (page == 1) {
            val document = app.get(pageUrl).document
            val home     = document.select("article").mapNotNull { it.toSearchResult() }
            return newHomePageResponse(request.name, home, true)
        }

        val pageDoc = app.get(pageUrl).document
        val nonce   = Regex("\"nonce\":\"([^\"]+)\"").find(pageDoc.html())?.groupValues?.get(1) ?: ""

        val filterEl  = pageDoc.selectFirst("[data-taxonomy]")
        val taxonomy  = filterEl?.attr("data-taxonomy") ?: "none"
        val termVal   = filterEl?.attr("data-term")     ?: "none"
        val searchVal = filterEl?.attr("data-search")   ?: "none"
        val typeVal   = filterEl?.attr("data-type")     ?: "none"

        val vars = """{"_wpsearch":"$nonce","taxonomy":"$taxonomy","search":"$searchVal","term":"$termVal","type":"$typeVal","genres":[],"years":[],"sort":1,"page":$page}"""

        val response = app.post(
            "$mainUrl/wp-admin/admin-ajax.php",
            data = mapOf("action" to "action_search", "vars" to vars),
            headers = mapOf(
                "Content-Type"     to "application/x-www-form-urlencoded",
                "X-WP-Nonce"       to nonce,
                "X-Requested-With" to "XMLHttpRequest",
                "Referer"          to pageUrl
            )
        ).text

        val json    = parseJson<AjaxResponse>(response)
        val htmlDoc = Jsoup.parse(json.html)
        val home    = htmlDoc.select("article").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home, json.next)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val href      = this.selectFirst("a.lnk-blk")?.attr("href") ?: return null
        var posterUrl = this.selectFirst("div figure img")?.attr("src")
        if (posterUrl!!.contains("data:image")) {
            posterUrl = this.selectFirst("div figure img")?.attr("data-lazy-src")
        }
        val imgAlt = this.selectFirst("div figure img")?.attr("alt")?.trim()
        val h2Text = this.selectFirst("header h2")?.text()?.trim()
        val title  = when {
            !imgAlt.isNullOrEmpty() && !imgAlt.contains("anime", ignoreCase = true) && imgAlt.length > 2 -> imgAlt
            !h2Text.isNullOrEmpty() && !h2Text.contains("AnimeDekho", ignoreCase = true) && h2Text.length > 2 -> h2Text
            else -> href.trimEnd('/').substringAfterLast("/").replace("-", " ").replaceFirstChar { it.uppercase() }
        }
        return newAnimeSearchResponse(title, Gson().toJson(Media(href, posterUrl)), TvType.Anime, false) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<AnimeSearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("ul[data-results] li article").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val media = Gson().fromJson(url, Media::class.java)

        val document = try {
            app.get(
                media.url,
                headers = mapOf(
                    "User-Agent"      to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                    "Referer"         to mainUrl,
                    "Accept"          to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "en-US,en;q=0.5",
                ),
                timeout = 30
            ).document
        } catch (e: Exception) {
            Log.e("AnimeDekho", "Failed to load page: ${e.message}")
            return newMovieLoadResponse("Error", url, TvType.Movie, url) {
                this.posterUrl = media.poster
            }
        }

        val rawTitle = listOfNotNull(
            document.selectFirst("h1.entry-title")?.text()?.trim(),
            document.selectFirst("h1")?.text()?.trim(),
            document.selectFirst("meta[property=og:title]")?.attr("content")?.trim(),
            document.selectFirst("meta[name=twitter:title]")?.attr("content")?.trim(),
            document.selectFirst("title")?.text()?.trim()
        ).mapNotNull { extractRawTitle(it) }.firstOrNull()
            ?: media.url.trimEnd('/').substringAfterLast("/")
                .replace("-", " ")
                .replaceFirstChar { it.uppercase() }

        val cleanTitle = cleanTitleText(rawTitle)
        val poster = fixUrlNull(document.selectFirst("div.post-thumbnail figure img")?.attr("src") ?: media.poster)
        val plot = document.selectFirst("div.entry-content p")?.text()?.trim() ?: document.selectFirst("meta[name=twitter:description]")?.attr("content")
        
        var year = document.selectFirst("span.year")?.text()?.trim()?.toIntOrNull()
        if (year == null) {
            year = fetchYearViaAjax(media.url, document.html())
        }

        val lst = document.select("ul.seasons-lst li")
        val isSeries = lst.isNotEmpty()

        // ── Fetch TMDB Details ──
        val tmdbDetails = fetchTmdbDetails(document, cleanTitle, isSeries, year)
        
        // ── Description Fallback Logic ──
        val finalPlot = if (!tmdbDetails.overview.isNullOrBlank()) {
            tmdbDetails.overview
        } else {
            plot
        }

        return if (!isSeries) {
            newMovieLoadResponse(rawTitle, url, TvType.Movie, Gson().toJson(Media(media.url, mediaType = 1))) {
                this.posterUrl           = poster
                this.backgroundPosterUrl = tmdbDetails.backdrop ?: poster
                this.plot                = finalPlot
                this.year                = year
                this.logoUrl             = tmdbDetails.logo
            }
        } else {
            // ─── Phase 1: Parse Raw Site Episodes ───
            val rawEpisodes = lst.mapNotNull {
                val name = it.selectFirst("h3.title")?.ownText() ?: "null"
                val href = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val epPoster = it.selectFirst("div > div > figure > img")?.attr("src")
                val seasonStr = it.selectFirst("h3.title > span")?.text()?.substringAfter("S")?.substringBefore("-")
                val season = seasonStr?.toIntOrNull() // Returns null if 'No Season' or invalid format
                
                SiteEpisode(href, name, epPoster, season)
            }

            // ─── Phase 2: Fix Episode Numbering (1-based per season) ───
            val seasonCounters = mutableMapOf<Int?, Int>()
            rawEpisodes.forEach { ep ->
                val count = seasonCounters.getOrDefault(ep.season, 0) + 1
                seasonCounters[ep.season] = count
                ep.calculatedEpNum = count
            }

            // ─── Phase 3: Smart TMDB Episode Fetching ───
            if (tmdbDetails.id != null && tmdbDetails.type == "tv") {
                val seasonsGrouped = rawEpisodes.groupBy { it.season }
                
                seasonsGrouped.forEach { (seasonNum, eps) ->
                    // Skip TMDB fetch if season is 'No Season' (null) or 0 (Specials)
                    if (seasonNum == null || seasonNum == 0) {
                        return@forEach
                    }
                    
                    // Skip TMDB fetch if any episode in this season is merged (contains "/")
                    val hasMergedEpisodes = eps.any { it.rawName.contains("/") }
                    
                    if (!hasMergedEpisodes) {
                        try {
                            val tmdbSeason = app.get("$TMDB_API/tv/${tmdbDetails.id}/season/$seasonNum?api_key=$TMDB_KEY")
                                .parsedSafe<TmdbSeason>()
                                
                            if (tmdbSeason?.episodes != null) {
                                val tmdbEpMap = tmdbSeason.episodes.associateBy { it.episodeNumber }
                                
                                eps.forEach { ep ->
                                    val tmdbData = tmdbEpMap[ep.calculatedEpNum]
                                    if (tmdbData != null) {
                                        if (!tmdbData.name.isNullOrBlank()) {
                                            ep.finalName = tmdbData.name
                                        }
                                        if (!tmdbData.stillPath.isNullOrBlank()) {
                                            ep.finalPoster = "$TMDB_IMG${tmdbData.stillPath}"
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("AnimeDekho", "TMDB season fetch failed: ${e.message}")
                        }
                    }
                }
            }

            // ─── Phase 4: Build Cloudstream Episodes ───
            val episodes = rawEpisodes.map { ep ->
                newEpisode(Gson().toJson(Media(ep.href, mediaType = 2))) {
                    this.name      = ep.finalName
                    this.posterUrl = ep.finalPoster
                    this.season    = ep.season
                    this.episode   = ep.calculatedEpNum
                }
            }

            val recommendations = document.select("div.swiper-wrapper article").mapNotNull {
                val recName   = it.selectFirst("h2")?.text()          ?: return@mapNotNull null
                val recHref   = it.selectFirst("a")?.attr("href")     ?: return@mapNotNull null
                val recPoster = it.selectFirst("figure img")?.attr("src")
                newTvSeriesSearchResponse(recName, Gson().toJson(Media(recHref, recPoster, 0)), TvType.TvSeries) {
                    this.posterUrl = recPoster
                }
            }

            newTvSeriesLoadResponse(rawTitle, url, TvType.TvSeries, episodes) {
                this.posterUrl           = poster
                this.backgroundPosterUrl = tmdbDetails.backdrop ?: poster
                this.plot                = finalPlot
                this.year                = year
                this.logoUrl             = tmdbDetails.logo
                this.recommendations     = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val media = runCatching { Gson().fromJson(data, Media::class.java) }.getOrElse {
            Log.e("Error:", "Failed to parse media JSON $it")
            return false
        }

        val headers = mapOf("Cookie" to "toronites_server=vidstream")
        val doc = app.get(media.url, headers = headers).document
        doc.select("iframe.serversel[src]").forEach { iframe ->
            val serverUrl = iframe.attr("src")
            if (serverUrl.isBlank()) return@forEach
            val innerIframeUrl = runCatching {
                app.get(serverUrl).document.selectFirst("iframe[src]")?.attr("src")
            }.getOrNull()
            if (!innerIframeUrl.isNullOrBlank()) {
                loadExtractor(innerIframeUrl, subtitleCallback, callback)
            }
        }

        val bodyClass = runCatching {
            app.get(media.url).document.selectFirst("body")?.attr("class")
        }.getOrNull()

        val term = Regex("(?:term|postid)-(\\d+)").find(bodyClass ?: "")?.groupValues?.getOrNull(1)
        if (term.isNullOrEmpty()) {
            Log.e("Error:", "No postid/term ID found in body class: $bodyClass")
            return false
        }

        var success = false
        for (i in 0..10) {
            val iframeUrl = runCatching {
                app.get("$mainUrl/?trdekho=$i&trid=$term&trtype=${media.mediaType}")
                    .document.selectFirst("iframe")?.attr("src")
            }.getOrNull()
            if (!iframeUrl.isNullOrEmpty()) {
                Log.d("Error:", "Found iframe: $iframeUrl")
                runCatching {
                    loadExtractor(iframeUrl, subtitleCallback, callback)
                    success = true
                }.onFailure {
                    Log.e("Error:", "Failed to load extractor for $iframeUrl $it")
                }
            }
        }
        return success
    }

    data class Media(val url: String, val poster: String? = null, val mediaType: Int? = null)

    data class AjaxResponse(
        val next: Boolean,
        val html: String
    )
}

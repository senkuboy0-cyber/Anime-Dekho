package com.anime

import com.google.gson.Gson
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

// TMDB Data Classes (Without annotations for max compatibility)
data class TmdbImages(
    val logos: List<TmdbImage>? = null,
    val backdrops: List<TmdbImage>? = null
)
data class TmdbImage(
    val file_path: String? = null,
    val iso_639_1: String? = null
)
data class TmdbFind(
    val movie_results: List<TmdbResult>? = null,
    val tv_results: List<TmdbResult>? = null
)
data class TmdbResult(
    val id: Int? = null,
    val media_type: String? = null,
    val title: String? = null,
    val name: String? = null,
    val release_date: String? = null,
    val first_air_date: String? = null
)
data class TmdbSearch(
    val results: List<TmdbResult>? = null
)
data class TmdbSeason(
    val episodes: List<TmdbEpisode>? = null
)
data class TmdbEpisode(
    val episode_number: Int? = null,
    val season_number: Int? = null,
    val name: String? = null,
    val still_path: String? = null
)
data class TmdbDetails(
    val id: Int? = null,
    val type: String? = null,
    val logoUrl: String? = null,
    val backdropUrl: String? = null
)

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

    private val TMDB_API = "https://api.themoviedb.org/3"
    private val TMDB_KEY = "1865f43a0549ca50d341dd9ab8b29f49"
    private val TMDB_IMG = "https://image.tmdb.org/t/p/original"

    private val epRegex1 = Regex("(?i)\\s+\\d+[x×]\\d+.*")
    private val epRegex2 = Regex("(?i)\\s+Episode\\s+\\d+.*")
    private val seasonRegex = Regex("(?i)\\s+Season\\s+\\d+.*")
    private val fanDubRegex1 = Regex("(?i)\\s*fan\\s*dub.*")
    private val fanDubRegex2 = Regex("(?i)\\s*fandub.*")
    private val normalizeRegex = Regex("[^a-zA-Z0-9]")

    private fun getResultYear(result: TmdbResult): Int? {
        return (result.release_date ?: result.first_air_date)
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

    private fun normalizeTitle(s: String?): String {
        return s?.replace(normalizeRegex, "")?.lowercase() ?: ""
    }

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
            .substringAfter("AnimeDekho \u2013 ")
            .trim()
            .takeIf {
                it.isNotEmpty() &&
                it.length > 2 &&
                !it.equals("AnimeDekho", ignoreCase = true) &&
                !it.startsWith("|")
            }
    }

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

    private suspend fun fetchTmdbDetails(document: Document, title: String, isSeries: Boolean, year: Int?): TmdbDetails {
        return try {
            var tmdbId: Int? = null
            var actualMediaType = if (isSeries) "tv" else "movie"

            val safeTitle = encodeUri(title)
            val searchRes = app.get("$TMDB_API/search/multi?api_key=$TMDB_KEY&query=$safeTitle")
                .parsedSafe<TmdbSearch>()

            val validResults = searchRes?.results?.filter { it.media_type == "movie" || it.media_type == "tv" }
            val normTitle = normalizeTitle(title)

            val exactCandidates = validResults?.filter {
                normalizeTitle(it.title) == normTitle ||
                normalizeTitle(it.name) == normTitle
            } ?: emptyList()

            val exactMatch = pickBestResult(exactCandidates, year)

            if (exactMatch != null) {
                tmdbId = exactMatch.id
                actualMediaType = exactMatch.media_type ?: actualMediaType
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
                    actualMediaType = startsWithMatch.media_type ?: actualMediaType
                } else {
                    val imdbId = document.select("a[href*='imdb.com/title']").attr("href")
                        .substringAfter("title/").substringBefore("/")
                        .takeIf { it.startsWith("tt") }

                    if (imdbId != null) {
                        app.get("$TMDB_API/find/$imdbId?api_key=$TMDB_KEY&external_source=imdb_id")
                            .parsedSafe<TmdbFind>()
                            ?.let { findRes ->
                                val tvId    = findRes.tv_results?.firstOrNull()?.id
                                val movieId = findRes.movie_results?.firstOrNull()?.id

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

            if (tmdbId == null) return TmdbDetails(null, null, null, null)

            val images = app.get(
                "$TMDB_API/$actualMediaType/$tmdbId/images?api_key=$TMDB_KEY"
            ).parsedSafe<TmdbImages>()

            val logo = images?.logos?.firstOrNull { it.iso_639_1 == "en" }
                ?: images?.logos?.firstOrNull { it.iso_639_1 == null }
                ?: images?.logos?.firstOrNull { it.iso_639_1 == "ja" }
                ?: images?.logos?.firstOrNull()

            val backdrop = images?.backdrops?.firstOrNull { it.iso_639_1 == null }
                ?: images?.backdrops?.firstOrNull { it.iso_639_1 == "en" }
                ?: images?.backdrops?.firstOrNull()

            val logoUrl     = logo?.file_path?.let { "$TMDB_IMG$it" }
            val backdropUrl = backdrop?.file_path?.let { "$TMDB_IMG$it" }

            TmdbDetails(tmdbId, actualMediaType, logoUrl, backdropUrl)

        } catch (e: Exception) {
            TmdbDetails(null, null, null, null)
        }
    }

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
        val href = this.selectFirst("a.lnk-blk")?.attr("href") ?: return null
        
        var posterUrl = this.selectFirst("div figure img")?.attr("src")
        if (posterUrl != null && posterUrl.contains("data:image")) {
            posterUrl = this.selectFirst("div figure img")?.attr("data-lazy-src")
        }
        
        val imgAlt = this.selectFirst("div figure img")?.attr("alt")?.trim()
        val h2Text = this.selectFirst("header h2")?.text()?.trim()
        
        val title = when {
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
        val media = try {
            Gson().fromJson(url, Media::class.java)
        } catch (e: Exception) {
            Media(url)
        }

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

        val poster = fixUrlNull(
            document.selectFirst("div.post-thumbnail figure img")?.attr("src") ?: media.poster
        )
        val plot = document.selectFirst("div.entry-content p")?.text()?.trim()
            ?: document.selectFirst("meta[name=twitter:description]")?.attr("content")
        
        var year = document.selectFirst("span.year")?.text()?.trim()?.toIntOrNull()

        if (year == null) {
            year = fetchYearViaAjax(media.url, document.html())
        }

        val lst      = document.select("ul.seasons-lst li")
        val isSeries = lst.isNotEmpty()

        val tmdbDetails = fetchTmdbDetails(document, cleanTitle, isSeries, year)
        val logoUrl     = tmdbDetails.logoUrl
        val backdropUrl = tmdbDetails.backdropUrl

        return if (!isSeries) {
            newMovieLoadResponse(rawTitle, url, TvType.Movie, Gson().toJson(Media(media.url, mediaType = 1))) {
                this.posterUrl           = poster
                this.backgroundPosterUrl = backdropUrl ?: poster
                this.plot                = plot
                this.year                = year
                this.logoUrl             = logoUrl
            }
        } else {
            val seasonsOnSite = lst.mapNotNull {
                it.selectFirst("h3.title > span")?.text()?.substringAfter("S")?.substringBefore("-")?.trim()?.toIntOrNull()
            }.distinct()

            val tmdbEpisodes = ArrayList<TmdbEpisode>()
            if (tmdbDetails.id != null && tmdbDetails.type == "tv") {
                for (s in seasonsOnSite) {
                    try {
                        val seasonRes = app.get("$TMDB_API/tv/${tmdbDetails.id}/season/$s?api_key=$TMDB_KEY").parsedSafe<TmdbSeason>()
                        if (seasonRes?.episodes != null) {
                            tmdbEpisodes.addAll(seasonRes.episodes)
                        }
                    } catch (e: Exception) {
                        Log.e("AnimeDekho", "Failed to fetch TMDB season $s: ${e.message}")
                    }
                }
            }

            val seasonCounters = HashMap<Int, Int>()

            val episodes = lst.mapNotNull { epElement ->
                val href = epElement.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val rawName = epElement.selectFirst("h3.title")?.ownText()?.trim() ?: "Episode"
                
                var epPoster = epElement.selectFirst("div > div > figure > img")?.attr("src")
                if (epPoster != null && epPoster.contains("data:image")) {
                    epPoster = epElement.selectFirst("div > div > figure > img")?.attr("data-lazy-src")
                }
                
                val spanText = epElement.selectFirst("h3.title > span")?.text() ?: ""
                val season = spanText.substringAfter("S", "1").substringBefore("-").trim().toIntOrNull() ?: 1
                
                val currentCount = seasonCounters[season] ?: 0
                val relativeEpNum = currentCount + 1
                seasonCounters[season] = relativeEpNum
                
                var finalName = rawName
                var finalPoster = epPoster
                var finalEpNum = relativeEpNum

                if (tmdbEpisodes.isNotEmpty()) {
                    val tmdbEp = tmdbEpisodes.find { it.season_number == season && it.episode_number == relativeEpNum }
                    if (tmdbEp != null) {
                        if (!tmdbEp.name.isNullOrBlank()) finalName = tmdbEp.name
                        if (!tmdbEp.still_path.isNullOrBlank()) finalPoster = "$TMDB_IMG${tmdbEp.still_path}"
                        if (tmdbEp.episode_number != null) finalEpNum = tmdbEp.episode_number
                    }
                }

                newEpisode(Gson().toJson(Media(href, mediaType = 2))) {
                    this.name      = finalName
                    this.posterUrl = finalPoster
                    this.season    = season
                    this.episode   = finalEpNum
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
                this.backgroundPosterUrl = backdropUrl ?: poster
                this.plot                = plot
                this.year                = year
                this.logoUrl             = logoUrl
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
        
        val iframes = doc.select("iframe.serversel[src]")
        for (iframe in iframes) {
            val serverUrl = iframe.attr("src")
            if (serverUrl.isBlank()) continue
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

        val termMatch = Regex("(?:term|postid)-(\\d+)").find(bodyClass ?: "")
        val term = if (termMatch != null && termMatch.groupValues.size > 1) termMatch.groupValues[1] else null
        
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

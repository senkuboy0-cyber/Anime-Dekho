package com.anime

import com.fasterxml.jackson.annotation.JsonProperty
import com.google.gson.Gson
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import kotlin.math.abs

// --- TMDB Data Classes ---
data class TmdbImages(
    @JsonProperty("logos") val logos: List<TmdbImage>? = null,
    @JsonProperty("backdrops") val backdrops: List<TmdbImage>? = null
)

data class TmdbImage(
    @JsonProperty("file_path") val filePath: String? = null,
    @JsonProperty("iso_639_1") val lang: String? = null
)

data class TmdbFind(
    @JsonProperty("movie_results") val movies: List<TmdbResult>? = null,
    @JsonProperty("tv_results") val tvShows: List<TmdbResult>? = null
)

data class TmdbResult(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("media_type") val mediaType: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    @JsonProperty("genre_ids") val genreIds: List<Int>? = null
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
    val backdrop: String?
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

open class AnimeDekhoProvider : MainAPI() {
    override var mainUrl = "https://animedekho.app"
    override var name = "Anime Dekho"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Cartoon,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Movie,
    )

    // --- TMDB API Constants ---
    private val TMDB_API = "https://api.themoviedb.org/3"
    private val TMDB_KEY = "1865f43a0549ca50d341dd9ab8b29f49"
    private val TMDB_IMG = "https://image.tmdb.org/t/p/original"

    private val normalizeRegex = Regex("[^a-zA-Z0-9]")

    // Initializing supported custom extractors
    private val zephyrflick  = Zephyrflick()
    private val awsStream    = AWSStream()
    private val abyss        = Abyss()
    private val streamRuby   = StreamRuby()
    private val cloudy       = Cloudy()
    private val upnsPlayer   = UpnsPlayer()
    private val gdMirrorbot  = GDMirrorbot()
    private val filesForever = FilesForever()
    private val emTurboVid   = EmTurboVid()
    private val vidMolyNet   = VidMolyNet()
    private val blakite      = Blakite()

    /**
     * Matches the provider URL with the appropriate extractor instance.
     */
    private suspend fun invokeExtractor(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            when {
                url.contains("zephyrflick", true) || url.contains("as-cdn", true) -> { zephyrflick.getUrl(url, referer, subtitleCallback, callback); true }
                url.contains("awstream", true) -> { awsStream.getUrl(url, referer, subtitleCallback, callback); true }
                url.contains("abyssplayer", true) || url.contains("playhydrax", true) -> { abyss.getUrl(url, referer, subtitleCallback, callback); true }
                url.contains("rubystm", true) || url.contains("streamruby", true) -> { streamRuby.getUrl(url, referer, subtitleCallback, callback); true }
                url.contains("cloudy.upns", true) -> { cloudy.getUrl(url, referer, subtitleCallback, callback); true }
                url.contains("upns", true) || url.contains("p2pplay", true) -> { upnsPlayer.getUrl(url, referer, subtitleCallback, callback); true }
                url.contains("gdmirrorbot.nl", true) -> { gdMirrorbot.getUrl(url, referer, subtitleCallback, callback); true }
                url.contains("filesforever", true) -> { filesForever.getUrl(url, referer, subtitleCallback, callback); true }
                url.contains("emturbovid", true) || url.contains("turboviplay", true) -> { emTurboVid.getUrl(url, referer, subtitleCallback, callback); true }
                url.contains("vidmoly", true) -> { vidMolyNet.getUrl(url, referer, subtitleCallback, callback); true }
                url.contains("blakite", true) -> { blakite.getUrl(url, referer, subtitleCallback, callback); true }
                // Fallback for generic extractors registered in Cloudstream
                else -> loadExtractor(url, referer, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            Log.e("AnimeDekho", "Extractor failed for $url: ${e.message}")
            false
        }
    }

    private fun getResultYear(result: TmdbResult): Int? {
        val dateString = result.releaseDate ?: result.firstAirDate
        return dateString?.substringBefore("-")?.toIntOrNull()
    }

    private fun yearMatches(tmdbYear: Int?, siteYear: Int?): Boolean {
        if (siteYear == null || tmdbYear == null) return true
        return abs(tmdbYear - siteYear) <= 1
    }

    private fun pickBestResult(candidates: List<TmdbResult>, siteYear: Int?): TmdbResult? {
        if (candidates.isEmpty()) return null
        if (siteYear != null) {
            val matched = candidates.filter { yearMatches(getResultYear(it), siteYear) }
            if (matched.isNotEmpty()) {
                if (matched.size == 1) return matched.first()
                return matched.firstOrNull { it.genreIds?.contains(16) == true } ?: matched.first()
            }
        }
        return candidates.first()
    }

    private fun cleanTitleText(title: String): String {
        return title.replace(Regex("(?i)Watch Online"), "")
            .replace(Regex("(?i)\\s+\\d+[x×]\\d+.*"), "")
            .replace(Regex("(?i)\\s+Episode\\s+\\d+.*"), "")
            .replace(Regex("(?i)\\s+Season\\s+\\d+.*"), "")
            .replace(Regex("(?i)\\s*fan\\s*dub.*"), "")
            .replace(Regex("(?i)\\s*fandub.*"), "")
            .substringBefore("(")
            .substringBefore("[")
            .trim()
    }

    private fun encodeUri(text: String): String {
        return try {
            URLEncoder.encode(text, "UTF-8")
        } catch (e: Exception) {
            text.replace(" ", "%20")
        }
    }

    private fun normalizeTitle(s: String?): String {
        return s?.replace(normalizeRegex, "")?.lowercase() ?: ""
    }

    private fun extractRawTitle(title: String): String? {
        val processed = title.replace(Regex("(?i)Watch Online "), "")
            .replace(Regex("(?i)\\s+Anime\\s*$"), "")
            .replace(Regex("(?i)\\s*Movie\\s*\\(.*Dubbed.*\\).*$"), "")
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

        return processed.takeIf { it.length > 2 && !it.equals("AnimeDekho", ignoreCase = true) && !it.startsWith("|") }
    }

    private suspend fun fetchAjaxData(movieUrl: String, pageHtml: String): Pair<String?, Int?> {
        return try {
            val nonce = Regex("\"nonce\"\\s*:\\s*\"([^\"]+)\"").find(pageHtml)?.groupValues?.get(1) ?: return Pair(null, null)
            val slug = movieUrl.trimEnd('/').substringAfterLast("/")
            
            val searchTerm = slug.replace(Regex("-(hin|hindi|dubbed|dub|sub)$", RegexOption.IGNORE_CASE), "")
                .replace("-", " ")
                .trim()
            
            val type = if (movieUrl.contains("series")) "series" else "movies"
            val vars = "{\"_wpsearch\":\"$nonce\",\"search\":\"$searchTerm\",\"type\":\"$type\",\"genres\":[],\"years\":[],\"sort\":1,\"page\":1}"

            val response = app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                data = mapOf("action" to "action_search", "vars" to vars),
                headers = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "X-WP-Nonce" to nonce,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to movieUrl
                )
            ).text

            val json = parseJson<AjaxResponse>(response)
            val doc = Jsoup.parse(json.html)
            
            val title = doc.selectFirst("h2.entry-title")?.text()?.trim()
                ?: doc.selectFirst(".post.info .entry-title")?.text()?.trim()
                ?: doc.selectFirst("img[alt]")?.attr("alt")?.trim()

            val year = Regex("<span class=\"year\">(\\d{4})</span>").find(json.html)?.groupValues?.get(1)?.toIntOrNull()
                ?: doc.selectFirst("span.year")?.text()?.trim()?.toIntOrNull()

            Pair(title, year)
        } catch (e: Exception) {
            Pair(null, null)
        }
    }

    private suspend fun fetchTmdbDetails(document: Document, title: String, isSeries: Boolean, year: Int?): TmdbDetails {
        return try {
            val safeTitle = encodeUri(title)
            val searchRes = app.get("$TMDB_API/search/multi?api_key=$TMDB_KEY&query=$safeTitle").parsedSafe<TmdbSearch>()
            
            val validResults = searchRes?.results?.filter { it.mediaType == "movie" || it.mediaType == "tv" } ?: emptyList()
            val normTitle = normalizeTitle(title)

            var bestMatch = pickBestResult(validResults.filter { normalizeTitle(it.title ?: it.name) == normTitle }, year)

            if (bestMatch == null && normTitle.length >= 6) {
                bestMatch = pickBestResult(validResults.filter {
                    val tn = normalizeTitle(it.title ?: it.name)
                    tn.isNotEmpty() && tn.startsWith(normTitle)
                }, year)
            }

            var tmdbId = bestMatch?.id
            var actualMediaType = bestMatch?.mediaType ?: if (isSeries) "tv" else "movie"

            if (tmdbId == null) {
                val imdbId = document.select("a[href*='imdb.com/title']").mapNotNull { link ->
                    val possibleId = link.attr("href").substringAfter("title/").substringBefore("/")
                    if (possibleId.startsWith("tt")) possibleId else null
                }.firstOrNull()

                if (imdbId != null) {
                    val findRes = app.get("$TMDB_API/find/$imdbId?api_key=$TMDB_KEY&external_source=imdb_id").parsedSafe<TmdbFind>()
                    val match = if (isSeries) findRes?.tvShows?.firstOrNull() ?: findRes?.movies?.firstOrNull()
                                else findRes?.movies?.firstOrNull() ?: findRes?.tvShows?.firstOrNull()
                    
                    if (match != null) {
                        tmdbId = match.id
                        actualMediaType = match.mediaType ?: actualMediaType
                    }
                }
            }

            if (tmdbId == null) return TmdbDetails(null, null, null, null)

            val images = app.get("$TMDB_API/$actualMediaType/$tmdbId/images?api_key=$TMDB_KEY").parsedSafe<TmdbImages>()
            var logoUrl: String? = null
            var backdropUrl: String? = null

            if (images != null) {
                val validLogos = images.logos?.filter { it.filePath?.endsWith(".svg", ignoreCase = true) != true } ?: emptyList()
                val bestLogo = validLogos.firstOrNull { it.lang == "en" }
                    ?: validLogos.firstOrNull { it.lang == null }
                    ?: validLogos.firstOrNull { it.lang == "ja" }
                    ?: validLogos.firstOrNull()
                bestLogo?.filePath?.let { logoUrl = "$TMDB_IMG$it" }

                val backdrops = images.backdrops ?: emptyList()
                val bestBackdrop = backdrops.firstOrNull { it.lang == null }
                    ?: backdrops.firstOrNull { it.lang == "en" }
                    ?: backdrops.firstOrNull()
                bestBackdrop?.filePath?.let { backdropUrl = "$TMDB_IMG$it" }
            }

            TmdbDetails(tmdbId, actualMediaType, logoUrl, backdropUrl)
        } catch (e: Exception) {
            TmdbDetails(null, null, null, null)
        }
    }

    private fun mainPageJson(taxonomy: String, search: String, term: String, type: String): String {
        return "{\"taxonomy\":\"$taxonomy\",\"search\":\"$search\",\"term\":\"$term\",\"type\":\"$type\"}"
    }

    override val mainPage = mainPageOf(
        mainPageJson("none", "none", "none", "series")        to "Series",
        mainPageJson("none", "none", "none", "movie")         to "Movies",
        mainPageJson("category", "none", "anime", "none")     to "Anime",
        mainPageJson("category", "none", "cartoon", "none")   to "Cartoon"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isSeries = request.data.contains("type\":\"series")
        val isMovie = request.data.contains("type\":\"movie")
        val isCategory = !isSeries && !isMovie

        if (isCategory) {
            val term = Regex("\"term\":\"([^\"]+)\"").find(request.data)?.groupValues?.get(1) ?: ""
            val pagedUrl = if (page > 1) "$mainUrl/category/$term/page/$page/" else "$mainUrl/category/$term/"
            
            val document = app.get(pagedUrl).document
            val home = document.select("article").mapNotNull { it.toSearchResult() }
            val hasNextPage = document.selectFirst("a.next.page-numbers") != null
            
            return newHomePageResponse(request.name, home, hasNextPage)
        }

        val pageUrl = if (isSeries) "$mainUrl/series-hindi/" else "$mainUrl/movie-hindi/"

        if (page == 1) {
            val document = app.get(pageUrl).document
            val home = document.select("article").mapNotNull { it.toSearchResult() }
            return newHomePageResponse(request.name, home, true)
        }

        val pageDoc = app.get(pageUrl).document
        val nonce = Regex("\"nonce\":\"([^\"]+)\"").find(pageDoc.html())?.groupValues?.get(1) ?: ""
        val filterEl = pageDoc.selectFirst("[data-taxonomy]")
        
        val taxonomy = filterEl?.attr("data-taxonomy") ?: "none"
        val termVal = filterEl?.attr("data-term") ?: "none"
        val searchVal = filterEl?.attr("data-search") ?: "none"
        val typeVal = filterEl?.attr("data-type") ?: "none"

        val vars = "{\"_wpsearch\":\"$nonce\",\"taxonomy\":\"$taxonomy\",\"search\":\"$searchVal\",\"term\":\"$termVal\",\"type\":\"$typeVal\",\"genres\":[],\"years\":[],\"sort\":1,\"page\":$page}"

        val response = app.post(
            "$mainUrl/wp-admin/admin-ajax.php",
            data = mapOf("action" to "action_search", "vars" to vars),
            headers = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded",
                "X-WP-Nonce" to nonce,
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to pageUrl
            )
        ).text

        val json = parseJson<AjaxResponse>(response)
        val htmlDoc = Jsoup.parse(json.html)
        val home = htmlDoc.select("article").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home, json.next)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val href = this.selectFirst("a.lnk-blk")?.attr("href") ?: return null
        val imgEl = this.selectFirst("div figure img")
        
        val posterUrl = imgEl?.let {
            val src = it.attr("src")
            if (src.contains("data:image")) it.attr("data-lazy-src") else src
        }
        
        val imgAlt = imgEl?.attr("alt")?.trim()
        val h2Text = this.selectFirst("header h2")?.text()?.trim()
        
        val title = when {
            !imgAlt.isNullOrEmpty() && !imgAlt.contains("anime", true) && imgAlt.length > 2 -> imgAlt
            !h2Text.isNullOrEmpty() && !h2Text.contains("AnimeDekho", true) && h2Text.length > 2 -> h2Text
            else -> href.trimEnd('/').substringAfterLast("/").replace("-", " ").replaceFirstChar { it.uppercase() }
        }
        
        return newAnimeSearchResponse(title, Gson().toJson(Media(href, posterUrl)), TvType.Anime, false) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val searchUrl = "$mainUrl/?s=$query"
        val html = app.get(searchUrl).document.html()
        
        val nonce = Regex("\"nonce\"\\s*:\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            ?: Regex("\"_wpsearch\"\\s*:\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1) ?: ""

        if (page == 1) {
            val document = Jsoup.parse(html)
            var elements = document.select("ul[data-results] li article")
            if (elements.isEmpty()) elements = document.select("article")
            
            val results = elements.mapNotNull { it.toSearchResult() }
            return newSearchResponseList(results, results.isNotEmpty())
        } else {
            if (nonce.isNotEmpty()) {
                val vars = "{\"_wpsearch\":\"$nonce\",\"taxonomy\":\"none\",\"search\":\"$query\",\"season\":\"none\",\"type\":\"mixed\",\"genres\":[],\"years\":[],\"sort\":\"1\",\"page\":$page}"
                
                val response = app.post(
                    url = "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf("action" to "action_search", "vars" to vars),
                    headers = mapOf(
                        "Content-Type" to "application/x-www-form-urlencoded",
                        "X-WP-Nonce" to nonce,
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to searchUrl
                    )
                ).text

                val json = parseJson<AjaxResponse>(response)
                val results = Jsoup.parse(json.html).select("article").mapNotNull { it.toSearchResult() }
                return newSearchResponseList(results, json.next)
            }
        }
        return newSearchResponseList(emptyList(), false)
    }

    override suspend fun load(url: String): LoadResponse {
        val media = try {
            Gson().fromJson(url, Media::class.java)
        } catch (e: Exception) {
            return newMovieLoadResponse("Error", url, TvType.Movie, url)
        } ?: return newMovieLoadResponse("Error", url, TvType.Movie, url)

        val document = try {
            app.get(
                media.url,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Referer" to mainUrl,
                ),
                timeout = 30
            ).document
        } catch (e: Exception) {
            return newMovieLoadResponse("Error", url, TvType.Movie, url) { this.posterUrl = media.poster }
        }
        
        val lst = document.select("ul.seasons-lst li")
        val isSeries = lst.isNotEmpty()
        var year = document.selectFirst("span.year")?.text()?.trim()?.toIntOrNull()

        var ajaxTitle: String? = null
        if (!isSeries || year == null) {
            val ajaxData = fetchAjaxData(media.url, document.html())
            if (!isSeries && !ajaxData.first.isNullOrEmpty()) {
                ajaxTitle = extractRawTitle(ajaxData.first!!)
            }
            if (year == null) {
                year = ajaxData.second
            }
        }

        val rawTitle: String = if (isSeries) {
            extractRawTitle(document.selectFirst("h1.entry-title")?.text().orEmpty())
                ?: extractRawTitle(document.selectFirst("h1")?.text().orEmpty())
                ?: extractRawTitle(document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty())
                ?: extractRawTitle(document.selectFirst("meta[name=twitter:title]")?.attr("content").orEmpty())
                ?: extractRawTitle(document.selectFirst("title")?.text().orEmpty())
                ?: media.url.trimEnd('/').substringAfterLast("/").replace("-", " ").replaceFirstChar { it.uppercase() }
        } else {
            val fallbackTitle = listOf(
                document.select("meta[name=twitter:title]").mapNotNull { it.attr("content") }
                    .firstOrNull { it.isNotBlank() && !it.contains("Watch Online", true) && !it.contains("AnimeDekho", true) },
                document.selectFirst("meta[name=twitter:title]")?.attr("content"),
                document.selectFirst("meta[property=og:title]")?.attr("content"),
                document.selectFirst("title")?.text()
            ).firstNotNullOfOrNull { text ->
                text?.let { extractRawTitle(it) ?: it }
                    ?.takeIf { 
                        it.length > 2 && 
                        !it.contains("SCHEDULE", true) && 
                        !it.contains("TIMING", true) &&
                        !it.equals("AnimeDekho", true)
                    }
            } ?: media.url.trimEnd('/').substringAfterLast("/")
                .replace("-", " ")
                .replaceFirstChar { it.uppercase() }

            ajaxTitle ?: fallbackTitle
        }

        val finalCleanTitle = cleanTitleText(rawTitle)
        val poster = fixUrlNull(document.selectFirst("div.post-thumbnail figure img")?.attr("src")) ?: media.poster
        val plot = document.selectFirst("div.entry-content p")?.text()?.trim() 
            ?: document.selectFirst("meta[name=twitter:description]")?.attr("content")
        
        val tmdbDetails = fetchTmdbDetails(document, finalCleanTitle, isSeries, year)

        if (!isSeries) {
            return newMovieLoadResponse(rawTitle, url, TvType.Movie, Gson().toJson(Media(media.url, mediaType = 1))) {
                this.posterUrl = poster
                this.backgroundPosterUrl = tmdbDetails.backdrop ?: poster
                this.plot = plot
                this.year = year
                this.logoUrl = tmdbDetails.logo
            }
        }

        val rawEpisodes = lst.mapNotNull { li ->
            val aEl = li.selectFirst("a") ?: return@mapNotNull null
            val name = li.selectFirst("h3.title")?.ownText() ?: "null"
            val href = aEl.attr("href")
            val epPoster = li.selectFirst("div > div > figure > img")?.attr("src")
            val season = li.selectFirst("h3.title > span")?.text()?.substringAfter("S")?.substringBefore("-")?.toIntOrNull()
            
            SiteEpisode(href, name, epPoster, season)
        }

        val seasonCounters = mutableMapOf<Int?, Int>()
        rawEpisodes.forEach { ep ->
            val count = (seasonCounters[ep.season] ?: 0) + 1
            seasonCounters[ep.season] = count
            ep.calculatedEpNum = count
        }

        if (tmdbDetails.id != null && tmdbDetails.type == "tv") {
            rawEpisodes.groupBy { it.season }.forEach { (seasonNum, eps) ->
                if (seasonNum != null && seasonNum != 0 && eps.none { it.rawName.contains("/") }) {
                    try {
                        val tmdbSeason = app.get("$TMDB_API/tv/${tmdbDetails.id}/season/$seasonNum?api_key=$TMDB_KEY").parsedSafe<TmdbSeason>()
                        tmdbSeason?.episodes?.associateBy { it.episodeNumber }?.let { tmdbEpMap ->
                            eps.forEach { ep ->
                                tmdbEpMap[ep.calculatedEpNum]?.let { tmdbData ->
                                    tmdbData.name?.takeIf { it.isNotEmpty() }?.let { ep.finalName = it }
                                    tmdbData.stillPath?.takeIf { it.isNotEmpty() }?.let { ep.finalPoster = "$TMDB_IMG$it" }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("AnimeDekho", "TMDB season fetch failed: ${e.message}")
                    }
                }
            }
        }

        val episodes = rawEpisodes.map { ep ->
            newEpisode(Gson().toJson(Media(ep.href, mediaType = 2))) {
                this.name = ep.finalName
                this.posterUrl = ep.finalPoster
                this.season = ep.season
                this.episode = ep.calculatedEpNum
            }
        }

        val recommendations = document.select("div.swiper-wrapper article").mapNotNull { recArticle ->
            val recName = recArticle.selectFirst("h2")?.text() ?: return@mapNotNull null
            val recHref = recArticle.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val recPoster = recArticle.selectFirst("figure img")?.attr("src")
            
            newTvSeriesSearchResponse(recName, Gson().toJson(Media(recHref, recPoster, 0)), TvType.TvSeries) {
                this.posterUrl = recPoster
            }
        }

        return newTvSeriesLoadResponse(rawTitle, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = tmdbDetails.backdrop ?: poster
            this.plot = plot
            this.year = year
            this.logoUrl = tmdbDetails.logo
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val media = try {
            Gson().fromJson(data, Media::class.java)
        } catch (e: Exception) {
            return false
        } ?: return false

        val isMovie = media.mediaType == 1

        val headers = mapOf("Cookie" to "toronites_server=vidstream")
        val doc = app.get(media.url, headers = headers).document

        val bodyClass = try {
            app.get(media.url).document.selectFirst("body")?.attr("class")
        } catch (e: Exception) { null }

        val term = bodyClass?.let { Regex("(?:term|postid)-(\\d+)").find(it)?.groupValues?.get(1) }
        var success = false

        if (isMovie) {
            coroutineScope {
                val serverUrls = doc.select("iframe.serversel[src]").map { it.attr("src") }.filter { it.isNotEmpty() }
                
                val jobs1 = serverUrls.map { serverUrl ->
                    async {
                        try {
                            val innerDoc = app.get(serverUrl).document
                            val innerIframeUrl = innerDoc.selectFirst("iframe[src]")?.attr("src")
                            if (!innerIframeUrl.isNullOrEmpty()) {
                                // Using custom invokeExtractor instead of loadExtractor
                                invokeExtractor(innerIframeUrl, media.url, subtitleCallback, callback)
                            }
                        } catch (e: Exception) {
                            // Ignore failure for individual server
                        }
                    }
                }

                val jobs2 = if (!term.isNullOrEmpty()) {
                    (0..10).map { i ->
                        async {
                            var localSuccess = false
                            try {
                                val iframeDoc = app.get("$mainUrl/?trdekho=$i&trid=$term&trtype=${media.mediaType}").document
                                val iframeUrl = iframeDoc.selectFirst("iframe")?.attr("src")
                                
                                if (!iframeUrl.isNullOrEmpty()) {
                                    // Using custom invokeExtractor instead of loadExtractor
                                    if (invokeExtractor(iframeUrl, media.url, subtitleCallback, callback)) {
                                        localSuccess = true
                                    }
                                }
                            } catch (e: Exception) {
                                // Ignore
                            }
                            localSuccess
                        }
                    }
                } else emptyList()

                jobs1.awaitAll()
                val results2 = jobs2.awaitAll()
                
                if (results2.any { it }) {
                    success = true
                }
            }
        } else {
            // Existing sequential behavior for Series / Episodes
            doc.select("iframe.serversel[src]").map { it.attr("src") }.filter { it.isNotEmpty() }.forEach { serverUrl ->
                try {
                    val innerDoc = app.get(serverUrl).document
                    val innerIframeUrl = innerDoc.selectFirst("iframe[src]")?.attr("src")
                    if (!innerIframeUrl.isNullOrEmpty()) {
                        // Using custom invokeExtractor instead of loadExtractor
                        invokeExtractor(innerIframeUrl, media.url, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    // Ignore failure for individual server
                }
            }

            if (!term.isNullOrEmpty()) {
                (0..10).forEach { i ->
                    try {
                        val iframeDoc = app.get("$mainUrl/?trdekho=$i&trid=$term&trtype=${media.mediaType}").document
                        val iframeUrl = iframeDoc.selectFirst("iframe")?.attr("src")
                        
                        if (!iframeUrl.isNullOrEmpty()) {
                            // Using custom invokeExtractor instead of loadExtractor
                            if (invokeExtractor(iframeUrl, media.url, subtitleCallback, callback)) {
                                success = true
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore
                    }
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

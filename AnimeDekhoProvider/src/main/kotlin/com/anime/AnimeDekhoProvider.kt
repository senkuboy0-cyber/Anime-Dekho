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
import java.util.HashMap
import java.util.ArrayList

// ─── TMDB Data Classes ───
data class TmdbImages(
    @JsonProperty("logos") val logos: ArrayList<TmdbImage>? = null,
    @JsonProperty("backdrops") val backdrops: ArrayList<TmdbImage>? = null
)
data class TmdbImage(
    @JsonProperty("file_path") val filePath: String? = null,
    @JsonProperty("iso_639_1") val lang: String?     = null
)
data class TmdbFind(
    @JsonProperty("movie_results") val movies: ArrayList<TmdbResult>? = null,
    @JsonProperty("tv_results")    val tvShows: ArrayList<TmdbResult>? = null
)
data class TmdbResult(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("media_type") val mediaType: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    @JsonProperty("genre_ids") val genreIds: ArrayList<Int>? = null
)
data class TmdbSearch(
    @JsonProperty("results") val results: ArrayList<TmdbResult>? = null
)
data class TmdbSeason(
    @JsonProperty("episodes") val episodes: ArrayList<TmdbEpisode>? = null
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
// ─────────────────────────

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

    // ─── TMDB API Features ──────────────────────────────────────────
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

    // ─── Safe Tracker ID Fetcher using Jikan API (Bypasses Site Block) ───
    private suspend fun fetchSafeTrackerData(animeTitle: String): Map<String, String> {
        val syncMap = HashMap<String, String>()
        try {
            if (animeTitle.isEmpty()) return syncMap
            
            val safeSearchTitle = encodeUri(animeTitle)
            val jikanUrl = "https://api.jikan.moe/v4/anime?q=$safeSearchTitle&limit=1"
            
            val response = app.get(
                jikanUrl,
                headers = mapOf(
                    "Accept" to "application/json",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                ),
                timeout = 15
            ).text
            
            val malIdMatch = Regex("\"mal_id\"\\s*:\\s*(\\d+)").find(response)
            
            if (malIdMatch != null && malIdMatch.groupValues.size > 1) {
                val foundId = malIdMatch.groupValues.get(1)
                syncMap.put("malId", foundId)
                Log.d("AnimeDekho", "Successfully fetched MAL ID for AniSkip: $foundId")
            }
        } catch (e: Exception) {
            Log.e("AnimeDekho", "Failed to fetch MAL ID from Jikan: ${e.message}")
        }
        return syncMap
    }

    private fun getResultYear(result: TmdbResult): Int? {
        var dateString = result.releaseDate
        if (dateString == null) {
            dateString = result.firstAirDate
        }
        
        if (dateString != null && dateString.contains("-")) {
            val yearString = dateString.substringBefore("-")
            return yearString.toIntOrNull()
        }
        return null
    }

    private fun yearMatches(tmdbYear: Int?, siteYear: Int?): Boolean {
        if (siteYear == null || tmdbYear == null) return true
        val diff = tmdbYear - siteYear
        return (diff == 0 || diff == 1 || diff == -1)
    }

    private fun pickBestResult(candidates: List<TmdbResult>, siteYear: Int?): TmdbResult? {
        if (candidates.isEmpty()) return null

        if (siteYear != null) {
            val yearMatched = ArrayList<TmdbResult>()
            for (i in 0 until candidates.size) {
                val candidate = candidates.get(i)
                if (yearMatches(getResultYear(candidate), siteYear)) {
                    yearMatched.add(candidate)
                }
            }

            if (yearMatched.size > 0) {
                if (yearMatched.size == 1) {
                    return yearMatched.get(0)
                }
                
                for (i in 0 until yearMatched.size) {
                    val match = yearMatched.get(i)
                    val genres = match.genreIds
                    if (genres != null) {
                        for (j in 0 until genres.size) {
                            if (genres.get(j) == 16) {
                                return match
                            }
                        }
                    }
                }
                
                return yearMatched.get(0)
            }
        }

        return candidates.get(0)
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
        if (s == null) return ""
        return s.replace(normalizeRegex, "").lowercase()
    }

    private fun extractRawTitle(title: String): String? {
        val processed = title
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
            
        if (processed.isNotEmpty() && processed.length > 2 && !processed.equals("AnimeDekho", ignoreCase = true) && !processed.startsWith("|")) {
            return processed
        }
        return null
    }

    private suspend fun fetchYearViaAjax(movieUrl: String, pageHtml: String): Int? {
        return try {
            val nonceMatch = Regex("\"nonce\"\\s*:\\s*\"([^\"]+)\"").find(pageHtml)
            if (nonceMatch == null) return null
            
            val nonce = nonceMatch.groupValues.get(1)
            var slug = movieUrl.trimEnd('/')
            val lastSlashIndex = slug.lastIndexOf("/")
            if (lastSlashIndex != -1) {
                slug = slug.substring(lastSlashIndex + 1)
            }
            
            val searchTerm = slug.replace(Regex("-(hin|hindi|dubbed|dub|sub)$", RegexOption.IGNORE_CASE), "")
                                 .replace("-", " ")
                                 .trim()
                                 
            var type = "movies"
            if (movieUrl.contains("series")) {
                type = "series"
            }

            val vars = "{\"_wpsearch\":\"" + nonce + "\",\"search\":\"" + searchTerm + "\",\"type\":\"" + type + "\",\"genres\":[],\"years\":[],\"sort\":1,\"page\":1}"

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
            val yearMatch = Regex("<span class=\"year\">(\\d{4})</span>").find(json.html)
            if (yearMatch != null) {
                return yearMatch.groupValues.get(1).toIntOrNull()
            }
            return null
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun fetchTmdbDetails(document: Document, title: String, isSeries: Boolean, year: Int?): TmdbDetails {
        return try {
            var tmdbId: Int? = null
            var actualMediaType = "movie"
            if (isSeries) {
                actualMediaType = "tv"
            }

            val safeTitle = encodeUri(title)

            val searchRes = app.get("$TMDB_API/search/multi?api_key=$TMDB_KEY&query=$safeTitle")
                .parsedSafe<TmdbSearch>()

            val validResults = ArrayList<TmdbResult>()
            if (searchRes != null && searchRes.results != null) {
                for (i in 0 until searchRes.results.size) {
                    val res = searchRes.results.get(i)
                    if (res.mediaType == "movie" || res.mediaType == "tv") {
                        validResults.add(res)
                    }
                }
            }
            
            val normTitle = normalizeTitle(title)

            val exactCandidates = ArrayList<TmdbResult>()
            for (i in 0 until validResults.size) {
                val res = validResults.get(i)
                if (normalizeTitle(res.title) == normTitle || normalizeTitle(res.name) == normTitle) {
                    exactCandidates.add(res)
                }
            }

            val exactMatch = pickBestResult(exactCandidates, year)

            if (exactMatch != null) {
                tmdbId = exactMatch.id
                if (exactMatch.mediaType != null) {
                    actualMediaType = exactMatch.mediaType
                }
            } else {
                val startsWithCandidates = ArrayList<TmdbResult>()
                if (normTitle.length >= 6) {
                    for (i in 0 until validResults.size) {
                        val res = validResults.get(i)
                        var tmdbNorm = ""
                        if (res.title != null) {
                            tmdbNorm = normalizeTitle(res.title)
                        } else if (res.name != null) {
                            tmdbNorm = normalizeTitle(res.name)
                        }
                        
                        if (tmdbNorm.isNotEmpty() && tmdbNorm.startsWith(normTitle)) {
                            startsWithCandidates.add(res)
                        }
                    }
                }

                val startsWithMatch = pickBestResult(startsWithCandidates, year)

                if (startsWithMatch != null) {
                    tmdbId = startsWithMatch.id
                    if (startsWithMatch.mediaType != null) {
                        actualMediaType = startsWithMatch.mediaType
                    }
                } else {
                    var imdbId: String? = null
                    val imdbLinks = document.select("a[href*='imdb.com/title']")
                    for (i in 0 until imdbLinks.size) {
                        val link = imdbLinks.get(i)
                        val href = link.attr("href")
                        if (href.contains("title/")) {
                            val afterTitle = href.substringAfter("title/")
                            val possibleId = afterTitle.substringBefore("/")
                            if (possibleId.startsWith("tt")) {
                                imdbId = possibleId
                                break
                            }
                        }
                    }

                    if (imdbId != null) {
                        val findRes = app.get("$TMDB_API/find/$imdbId?api_key=$TMDB_KEY&external_source=imdb_id")
                            .parsedSafe<TmdbFind>()
                            
                        if (findRes != null) {
                            var tvId: Int? = null
                            if (findRes.tvShows != null && findRes.tvShows.size > 0) {
                                tvId = findRes.tvShows.get(0).id
                            }
                            
                            var movieId: Int? = null
                            if (findRes.movies != null && findRes.movies.size > 0) {
                                movieId = findRes.movies.get(0).id
                            }

                            if (isSeries) {
                                if (tvId != null) { 
                                    tmdbId = tvId
                                    actualMediaType = "tv"    
                                } else if (movieId != null) { 
                                    tmdbId = movieId
                                    actualMediaType = "movie" 
                                }
                            } else {
                                if (movieId != null) { 
                                    tmdbId = movieId
                                    actualMediaType = "movie" 
                                } else if (tvId != null) { 
                                    tmdbId = tvId
                                    actualMediaType = "tv"    
                                }
                            }
                        }
                    }
                }
            }

            if (tmdbId == null) return TmdbDetails(null, null, null, null)

            val images = app.get(
                "$TMDB_API/$actualMediaType/$tmdbId/images?api_key=$TMDB_KEY"
            ).parsedSafe<TmdbImages>()

            var logoUrl: String? = null
            var backdropUrl: String? = null

            if (images != null) {
                if (images.logos != null) {
                    val validLogos = ArrayList<TmdbImage>()
                    for (i in 0 until images.logos.size) {
                        val logo = images.logos.get(i)
                        var path = logo.filePath
                        if (path == null) path = ""
                        
                        if (!path.endsWith(".svg") && !path.endsWith(".SVG")) {
                            validLogos.add(logo)
                        }
                    }
                    
                    var bestLogo: TmdbImage? = null
                    for (i in 0 until validLogos.size) {
                        val logo = validLogos.get(i)
                        if (logo.lang == "en") {
                            bestLogo = logo
                            break
                        }
                    }
                    if (bestLogo == null) {
                        for (i in 0 until validLogos.size) {
                            val logo = validLogos.get(i)
                            if (logo.lang == null) {
                                bestLogo = logo
                                break
                            }
                        }
                    }
                    if (bestLogo == null) {
                        for (i in 0 until validLogos.size) {
                            val logo = validLogos.get(i)
                            if (logo.lang == "ja") {
                                bestLogo = logo
                                break
                            }
                        }
                    }
                    if (bestLogo == null && validLogos.size > 0) {
                        bestLogo = validLogos.get(0)
                    }
                    
                    if (bestLogo != null && bestLogo.filePath != null) {
                        logoUrl = "$TMDB_IMG${bestLogo.filePath}"
                    }
                }
                
                if (images.backdrops != null) {
                    var bestBackdrop: TmdbImage? = null
                    for (i in 0 until images.backdrops.size) {
                        val backdrop = images.backdrops.get(i)
                        if (backdrop.lang == null) {
                            bestBackdrop = backdrop
                            break
                        }
                    }
                    if (bestBackdrop == null) {
                        for (i in 0 until images.backdrops.size) {
                            val backdrop = images.backdrops.get(i)
                            if (backdrop.lang == "en") {
                                bestBackdrop = backdrop
                                break
                            }
                        }
                    }
                    if (bestBackdrop == null && images.backdrops.size > 0) {
                        bestBackdrop = images.backdrops.get(0)
                    }
                    
                    if (bestBackdrop != null && bestBackdrop.filePath != null) {
                        backdropUrl = "$TMDB_IMG${bestBackdrop.filePath}"
                    }
                }
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
        mainPageJson("none", "none", "none", "series")          to "Series",
        mainPageJson("none", "none", "none", "movie")           to "Movies",
        mainPageJson("category", "none", "anime", "none")       to "Anime",
        mainPageJson("category", "none", "cartoon", "none")     to "Cartoon",
        mainPageJson("category", "none", "hindi-dub", "none")   to "Hindi Dub",
        mainPageJson("category", "none", "tamil", "none")       to "Tamil",
        mainPageJson("category", "none", "telugu", "none")      to "Telugu"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        var isSeries = false
        var isMovie = false
        
        if (request.data.contains("type\":\"series")) {
            isSeries = true
        }
        if (request.data.contains("type\":\"movie")) {
            isMovie = true
        }
        
        val isCategory = !isSeries && !isMovie

        if (isCategory) {
            val termMatch = Regex("\"term\":\"([^\"]+)\"").find(request.data)
            var term = ""
            if (termMatch != null) {
                term = termMatch.groupValues.get(1)
            }
            
            val pageUrl   = "$mainUrl/category/$term/"
            var pagedUrl = pageUrl
            if (page > 1) {
                pagedUrl = "${pageUrl}page/$page/"
            }
            
            val document  = app.get(pagedUrl).document
            
            val articles = document.select("article")
            val home = ArrayList<AnimeSearchResponse>()
            for (i in 0 until articles.size) {
                val article = articles.get(i)
                val searchResult = article.toSearchResult()
                if (searchResult != null) {
                    home.add(searchResult)
                }
            }
            
            val hasNextPage = document.selectFirst("a.next.page-numbers") != null
            return newHomePageResponse(request.name, home, hasNextPage)
        }

        var pageUrl = "$mainUrl/movie-hindi/"
        if (isSeries) {
            pageUrl = "$mainUrl/series-hindi/"
        }

        if (page == 1) {
            val document = app.get(pageUrl).document
            val articles = document.select("article")
            val home = ArrayList<AnimeSearchResponse>()
            for (i in 0 until articles.size) {
                val article = articles.get(i)
                val searchResult = article.toSearchResult()
                if (searchResult != null) {
                    home.add(searchResult)
                }
            }
            return newHomePageResponse(request.name, home, true)
        }

        val pageDoc = app.get(pageUrl).document
        val nonceMatch = Regex("\"nonce\":\"([^\"]+)\"").find(pageDoc.html())
        var nonce = ""
        if (nonceMatch != null) {
            nonce = nonceMatch.groupValues.get(1)
        }

        val filterEl  = pageDoc.selectFirst("[data-taxonomy]")
        var taxonomy = "none"
        var termVal = "none"
        var searchVal = "none"
        var typeVal = "none"
        
        if (filterEl != null) {
            if (filterEl.hasAttr("data-taxonomy")) taxonomy = filterEl.attr("data-taxonomy")
            if (filterEl.hasAttr("data-term")) termVal = filterEl.attr("data-term")
            if (filterEl.hasAttr("data-search")) searchVal = filterEl.attr("data-search")
            if (filterEl.hasAttr("data-type")) typeVal = filterEl.attr("data-type")
        }

        val vars = "{\"_wpsearch\":\"" + nonce + "\",\"taxonomy\":\"" + taxonomy + "\",\"search\":\"" + searchVal + "\",\"term\":\"" + termVal + "\",\"type\":\"" + typeVal + "\",\"genres\":[],\"years\":[],\"sort\":1,\"page\":" + page + "}"

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

        val json = parseJson<AjaxResponse>(response)
        val htmlDoc = Jsoup.parse(json.html)
        
        val articles = htmlDoc.select("article")
        val home = ArrayList<AnimeSearchResponse>()
        for (i in 0 until articles.size) {
            val article = articles.get(i)
            val searchResult = article.toSearchResult()
            if (searchResult != null) {
                home.add(searchResult)
            }
        }

        return newHomePageResponse(request.name, home, json.next)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val linkEl = this.selectFirst("a.lnk-blk")
        if (linkEl == null) return null
        val href = linkEl.attr("href")
        
        var posterUrl: String? = null
        val imgEl = this.selectFirst("div figure img")
        if (imgEl != null) {
            posterUrl = imgEl.attr("src")
            if (posterUrl != null && posterUrl.contains("data:image")) {
                posterUrl = imgEl.attr("data-lazy-src")
            }
        }
        
        var imgAlt: String? = null
        if (imgEl != null) {
            imgAlt = imgEl.attr("alt")?.trim()
        }
        
        var h2Text: String? = null
        val h2El = this.selectFirst("header h2")
        if (h2El != null) {
            h2Text = h2El.text().trim()
        }
        
        var title = ""
        if (imgAlt != null && imgAlt.isNotEmpty() && !imgAlt.contains("anime", ignoreCase = true) && imgAlt.length > 2) {
            title = imgAlt
        } else if (h2Text != null && h2Text.isNotEmpty() && !h2Text.contains("AnimeDekho", ignoreCase = true) && h2Text.length > 2) {
            title = h2Text
        } else {
            var slug = href.trimEnd('/')
            val lastSlash = slug.lastIndexOf("/")
            if (lastSlash != -1) {
                slug = slug.substring(lastSlash + 1)
            }
            title = slug.replace("-", " ")
            if (title.isNotEmpty()) {
                title = title.substring(0, 1).uppercase() + title.substring(1)
            }
        }
        
        return newAnimeSearchResponse(title, Gson().toJson(Media(href, posterUrl)), TvType.Anime, false) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val results = ArrayList<SearchResponse>()
        var hasNext = false

        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document
        val html = document.html()
        
        var nonce = ""
        val nonceMatch1 = Regex("\"nonce\"\\s*:\\s*\"([^\"]+)\"").find(html)
        val nonceMatch2 = Regex("\"_wpsearch\"\\s*:\\s*\"([^\"]+)\"").find(html)
        
        if (nonceMatch1 != null) {
            nonce = nonceMatch1.groupValues.get(1)
        } else if (nonceMatch2 != null) {
            nonce = nonceMatch2.groupValues.get(1)
        }

        if (page == 1) {
            var elements = document.select("ul[data-results] li article")
            if (elements.isEmpty()) {
                elements = document.select("article")
            }
            
            for (i in 0 until elements.size) {
                val element = elements.get(i)
                val res = element.toSearchResult()
                if (res != null) {
                    results.add(res)
                }
            }
            
            if (results.size > 0) {
                hasNext = true
            }
        } else {
            if (nonce.isNotEmpty()) {
                val vars = "{\"_wpsearch\":\"" + nonce + "\",\"taxonomy\":\"none\",\"search\":\"" + query + "\",\"season\":\"none\",\"type\":\"mixed\",\"genres\":[],\"years\":[],\"sort\":\"1\",\"page\":" + page + "}"
                
                val response = app.post(
                    url = "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf("action" to "action_search", "vars" to vars),
                    headers = mapOf(
                        "Content-Type"     to "application/x-www-form-urlencoded",
                        "X-WP-Nonce"       to nonce,
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer"          to searchUrl
                    )
                ).text

                val json = parseJson<AjaxResponse>(response)
                val htmlDoc = Jsoup.parse(json.html)
                
                val elements = htmlDoc.select("article")
                for (i in 0 until elements.size) {
                    val element = elements.get(i)
                    val res = element.toSearchResult()
                    if (res != null) {
                        results.add(res)
                    }
                }
                hasNext = json.next
            }
        }

        return newSearchResponseList(results, hasNext)
    }

    override suspend fun load(url: String): LoadResponse {
        var media: Media? = null
        try {
            media = Gson().fromJson(url, Media::class.java)
        } catch (e: Exception) {
            Log.e("AnimeDekho", "Failed to parse media JSON: ${e.message}")
            return newMovieLoadResponse("Error", url, TvType.Movie, url)
        }

        if (media == null) return newMovieLoadResponse("Error", url, TvType.Movie, url)

        var document: Document? = null
        try {
            document = app.get(
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

        var rawTitle: String? = null
        
        val h1EntryTitle = document.selectFirst("h1.entry-title")?.text()?.trim()
        if (h1EntryTitle != null && extractRawTitle(h1EntryTitle) != null) {
            rawTitle = extractRawTitle(h1EntryTitle)
        }
        
        if (rawTitle == null) {
            val h1Title = document.selectFirst("h1")?.text()?.trim()
            if (h1Title != null && extractRawTitle(h1Title) != null) {
                rawTitle = extractRawTitle(h1Title)
            }
        }
        
        if (rawTitle == null) {
            val ogTitle = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            if (ogTitle != null && extractRawTitle(ogTitle) != null) {
                rawTitle = extractRawTitle(ogTitle)
            }
        }
        
        if (rawTitle == null) {
            val twitterTitle = document.selectFirst("meta[name=twitter:title]")?.attr("content")?.trim()
            if (twitterTitle != null && extractRawTitle(twitterTitle) != null) {
                rawTitle = extractRawTitle(twitterTitle)
            }
        }
        
        if (rawTitle == null) {
            val headTitle = document.selectFirst("title")?.text()?.trim()
            if (headTitle != null && extractRawTitle(headTitle) != null) {
                rawTitle = extractRawTitle(headTitle)
            }
        }
        
        if (rawTitle == null) {
            var slug = media.url.trimEnd('/')
            val lastSlash = slug.lastIndexOf("/")
            if (lastSlash != -1) {
                slug = slug.substring(lastSlash + 1)
            }
            rawTitle = slug.replace("-", " ")
            if (rawTitle.isNotEmpty()) {
                rawTitle = rawTitle.substring(0, 1).uppercase() + rawTitle.substring(1)
            }
        }

        var finalCleanTitle = ""
        if (rawTitle != null) {
            finalCleanTitle = cleanTitleText(rawTitle)
        }
        
        var poster = media.poster
        val docPoster = document.selectFirst("div.post-thumbnail figure img")?.attr("src")
        if (docPoster != null) {
            poster = fixUrlNull(docPoster)
        }
        
        var plot = document.selectFirst("div.entry-content p")?.text()?.trim()
        if (plot == null) {
            plot = document.selectFirst("meta[name=twitter:description]")?.attr("content")
        }
        
        var year: Int? = null
        val yearText = document.selectFirst("span.year")?.text()?.trim()
        if (yearText != null) {
            year = yearText.toIntOrNull()
        }
        if (year == null) {
            year = fetchYearViaAjax(media.url, document.html())
        }

        val lst = document.select("ul.seasons-lst li")
        var isSeries = false
        if (lst.size > 0) {
            isSeries = true
        }

        // ── Fetch TMDB Details ──
        val tmdbDetails = fetchTmdbDetails(document, finalCleanTitle, isSeries, year)

        // ── Fetch Safe Tracker Data for AniSkip ──
        val aniskipSyncMap = fetchSafeTrackerData(finalCleanTitle)

        if (!isSeries) {
            return newMovieLoadResponse(rawTitle ?: "", url, TvType.Movie, Gson().toJson(Media(media.url, mediaType = 1))) {
                this.posterUrl           = poster
                if (tmdbDetails.backdrop != null) {
                    this.backgroundPosterUrl = tmdbDetails.backdrop
                } else {
                    this.backgroundPosterUrl = poster
                }
                this.plot                = plot
                this.year                = year
                this.logoUrl             = tmdbDetails.logo
                
                if (aniskipSyncMap.isNotEmpty()) {
                    this.syncData = aniskipSyncMap
                }
            }
        } else {
            val rawEpisodes = ArrayList<SiteEpisode>()
            for (i in 0 until lst.size) {
                val li = lst.get(i)
                var name = "null"
                val h3El = li.selectFirst("h3.title")
                if (h3El != null) {
                    name = h3El.ownText()
                }
                
                val aEl = li.selectFirst("a")
                if (aEl != null) {
                    val href = aEl.attr("href")
                    val epPoster = li.selectFirst("div > div > figure > img")?.attr("src")
                    
                    var season: Int? = null
                    val seasonSpan = li.selectFirst("h3.title > span")
                    if (seasonSpan != null) {
                        val sText = seasonSpan.text()
                        if (sText.contains("S")) {
                            val afterS = sText.substringAfter("S")
                            if (afterS.contains("-")) {
                                season = afterS.substringBefore("-").toIntOrNull()
                            }
                        }
                    }
                    
                    rawEpisodes.add(SiteEpisode(href, name, epPoster, season))
                }
            }

            val seasonCounters = HashMap<Int?, Int>()
            for (i in 0 until rawEpisodes.size) {
                val ep = rawEpisodes.get(i)
                var count = 0
                if (seasonCounters.containsKey(ep.season)) {
                    val existingCount = seasonCounters.get(ep.season)
                    if (existingCount != null) {
                        count = existingCount
                    }
                }
                count += 1
                seasonCounters.put(ep.season, count)
                ep.calculatedEpNum = count
            }

            if (tmdbDetails.id != null && tmdbDetails.type == "tv") {
                val seasonsGrouped = HashMap<Int?, ArrayList<SiteEpisode>>()
                for (i in 0 until rawEpisodes.size) {
                    val ep = rawEpisodes.get(i)
                    if (!seasonsGrouped.containsKey(ep.season)) {
                        seasonsGrouped.put(ep.season, ArrayList<SiteEpisode>())
                    }
                    val seasonList = seasonsGrouped.get(ep.season)
                    if (seasonList != null) {
                        seasonList.add(ep)
                    }
                }
                
                val iterator = seasonsGrouped.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    val seasonNum = entry.key
                    val eps = entry.value
                    
                    if (seasonNum == null || seasonNum == 0) {
                        continue
                    }
                    
                    var hasMergedEpisodes = false
                    for (i in 0 until eps.size) {
                        val ep = eps.get(i)
                        if (ep.rawName.contains("/")) {
                            hasMergedEpisodes = true
                            break
                        }
                    }
                    
                    if (!hasMergedEpisodes) {
                        try {
                            val tmdbSeason = app.get("$TMDB_API/tv/${tmdbDetails.id}/season/$seasonNum?api_key=$TMDB_KEY")
                                .parsedSafe<TmdbSeason>()
                                
                            if (tmdbSeason != null && tmdbSeason.episodes != null) {
                                val tmdbEpMap = HashMap<Int, TmdbEpisode>()
                                for (j in 0 until tmdbSeason.episodes.size) {
                                    val tmdbEp = tmdbSeason.episodes.get(j)
                                    if (tmdbEp.episodeNumber != null) {
                                        tmdbEpMap.put(tmdbEp.episodeNumber, tmdbEp)
                                    }
                                }
                                
                                for (k in 0 until eps.size) {
                                    val ep = eps.get(k)
                                    if (tmdbEpMap.containsKey(ep.calculatedEpNum)) {
                                        val tmdbData = tmdbEpMap.get(ep.calculatedEpNum)
                                        if (tmdbData != null) {
                                            if (tmdbData.name != null && tmdbData.name.isNotEmpty()) {
                                                ep.finalName = tmdbData.name
                                            }
                                            if (tmdbData.stillPath != null && tmdbData.stillPath.isNotEmpty()) {
                                                ep.finalPoster = "$TMDB_IMG${tmdbData.stillPath}"
                                            }
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

            val episodes = ArrayList<Episode>()
            for (i in 0 until rawEpisodes.size) {
                val ep = rawEpisodes.get(i)
                episodes.add(
                    newEpisode(Gson().toJson(Media(ep.href, mediaType = 2))) {
                        this.name      = ep.finalName
                        this.posterUrl = ep.finalPoster
                        this.season    = ep.season
                        this.episode   = ep.calculatedEpNum
                    }
                )
            }

            val recommendations = ArrayList<SearchResponse>()
            val recArticles = document.select("div.swiper-wrapper article")
            for (i in 0 until recArticles.size) {
                val recArticle = recArticles.get(i)
                val h2El = recArticle.selectFirst("h2")
                val aEl = recArticle.selectFirst("a")
                
                if (h2El != null && aEl != null) {
                    val recName = h2El.text()
                    val recHref = aEl.attr("href")
                    
                    var recPoster: String? = null
                    val figureImg = recArticle.selectFirst("figure img")
                    if (figureImg != null) {
                        recPoster = figureImg.attr("src")
                    }
                    
                    recommendations.add(
                        newTvSeriesSearchResponse(recName, Gson().toJson(Media(recHref, recPoster, 0)), TvType.TvSeries) {
                            this.posterUrl = recPoster
                        }
                    )
                }
            }

            return newTvSeriesLoadResponse(rawTitle ?: "", url, TvType.TvSeries, episodes) {
                this.posterUrl           = poster
                if (tmdbDetails.backdrop != null) {
                    this.backgroundPosterUrl = tmdbDetails.backdrop
                } else {
                    this.backgroundPosterUrl = poster
                }
                this.plot                = plot
                this.year                = year
                this.logoUrl             = tmdbDetails.logo
                this.recommendations     = recommendations
                
                if (aniskipSyncMap.isNotEmpty()) {
                    this.syncData = aniskipSyncMap
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var media: Media? = null
        try {
            media = Gson().fromJson(data, Media::class.java)
        } catch (e: Exception) {
            Log.e("Error:", "Failed to parse media JSON ${e.message}")
            return false
        }
        
        if (media == null) return false

        val headers = mapOf("Cookie" to "toronites_server=vidstream")
        val doc = app.get(media.url, headers = headers).document
        
        val iframes = doc.select("iframe.serversel[src]")
        for (i in 0 until iframes.size) {
            val iframe = iframes.get(i)
            val serverUrl = iframe.attr("src")
            if (serverUrl.isNotEmpty()) {
                var innerIframeUrl: String? = null
                try {
                    val innerDoc = app.get(serverUrl).document
                    val innerIframe = innerDoc.selectFirst("iframe[src]")
                    if (innerIframe != null) {
                        innerIframeUrl = innerIframe.attr("src")
                    }
                } catch (e: Exception) {
                    // Ignore
                }
                
                if (innerIframeUrl != null && innerIframeUrl.isNotEmpty()) {
                    loadExtractor(innerIframeUrl, subtitleCallback, callback)
                }
            }
        }

        var bodyClass: String? = null
        try {
            val bodyEl = app.get(media.url).document.selectFirst("body")
            if (bodyEl != null) {
                bodyClass = bodyEl.attr("class")
            }
        } catch (e: Exception) {
            // Ignore
        }

        var term: String? = null
        if (bodyClass != null) {
            val termMatch = Regex("(?:term|postid)-(\\d+)").find(bodyClass)
            if (termMatch != null) {
                term = termMatch.groupValues.get(1)
            }
        }
        
        if (term == null || term.isEmpty()) {
            Log.e("Error:", "No postid/term ID found in body class: $bodyClass")
            return false
        }

        var success = false
        for (i in 0..10) {
            var iframeUrl: String? = null
            try {
                val iframeDoc = app.get("$mainUrl/?trdekho=$i&trid=$term&trtype=${media.mediaType}").document
                val iframeEl = iframeDoc.selectFirst("iframe")
                if (iframeEl != null) {
                    iframeUrl = iframeEl.attr("src")
                }
            } catch (e: Exception) {
                // Ignore
            }
            
            if (iframeUrl != null && iframeUrl.isNotEmpty()) {
                Log.d("Error:", "Found iframe: $iframeUrl")
                try {
                    loadExtractor(iframeUrl, subtitleCallback, callback)
                    success = true
                } catch (e: Exception) {
                    Log.e("Error:", "Failed to load extractor for $iframeUrl ${e.message}")
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

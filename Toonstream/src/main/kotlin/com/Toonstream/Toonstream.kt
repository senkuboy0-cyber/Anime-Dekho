package com.Toonstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.google.gson.Gson
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
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
import com.lagradost.cloudstream3.newSearchResponseList
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
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.ArrayList

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
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("genre_ids") val genreIds: ArrayList<Int>? = null
)

data class TmdbSearch(
    @JsonProperty("results") val results: ArrayList<TmdbResult>? = null
)

data class TmdbDetails(
    val logoUrl: String? = null,
    val backdropUrl: String? = null,
    val overview: String? = null
)

data class ServerInfo(val truelink: String, val referer: String, val priority: Int)

data class ToonMedia(val url: String, val poster: String?)

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
        var clean = title.replace(Regex("[\u200B-\u200D\uFEFF\\p{Cf}]"), "")
        clean = clean.replace("\u00A0", " ")
        clean = clean.replace(Regex("(?i)Watch Online"), "")
        clean = clean.replace("(?i)\\s+\\d+[x×]\\d+.*".toRegex(), "")
        clean = clean.replace("×", "x")
        clean = clean.replace("(?i)\\s+Episode\\s+\\d+.*".toRegex(), "")
        clean = clean.replace("(?i)\\s+Season\\s+\\d+.*".toRegex(), "")
        clean = clean.replace("(?i)\\s*hindi\\s*dub.*".toRegex(), "")
        clean = clean.replace("(?i)\\s*english\\s*dub.*".toRegex(), "")
        clean = clean.replace("(?i)\\s*dual\\s*audio.*".toRegex(), "")
        clean = clean.replace("(?i)\\s*multi\\s*audio.*".toRegex(), "")
        clean = clean.replace("(?i)\\s*fan\\s*dub.*".toRegex(), "")
        clean = clean.replace("(?i)\\s*fandub.*".toRegex(), "")
        clean = clean.substringBefore("(")
        clean = clean.substringBefore("[")
        clean = clean.replace("\\s+".toRegex(), " ")
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
        if (s == null) return ""
        return s.replace(Regex("[^a-zA-Z0-9]"), "").lowercase()
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

    private fun pickBestResult(candidates: ArrayList<TmdbResult>, siteYear: Int?): TmdbResult? {
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

    private suspend fun fetchTmdbAssets(document: Document?, title: String, isSeries: Boolean, year: Int?): TmdbDetails {
        return try {
            var tmdbId: Int? = null
            var actualMediaType = "movie"
            if (isSeries) {
                actualMediaType = "tv"
            }
            var tmdbOverview: String? = null

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
                var resNorm = ""
                if (res.title != null) resNorm = normalizeTitle(res.title)
                else if (res.name != null) resNorm = normalizeTitle(res.name)
                
                if (resNorm == normTitle) {
                    exactCandidates.add(res)
                }
            }

            val exactMatch = pickBestResult(exactCandidates, year)

            if (exactMatch != null) {
                tmdbId = exactMatch.id
                if (exactMatch.mediaType != null) {
                    actualMediaType = exactMatch.mediaType
                }
                tmdbOverview = exactMatch.overview
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
                    tmdbOverview = startsWithMatch.overview
                } else {
                    var imdbId: String? = null
                    if (document != null) {
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
                    }

                    if (imdbId != null) {
                        val findRes = app.get("$TMDB_API/find/$imdbId?api_key=$TMDB_KEY&external_source=imdb_id")
                            .parsedSafe<TmdbFind>()
                            
                        if (findRes != null) {
                            var tvMatch: TmdbResult? = null
                            if (findRes.tvShows != null && findRes.tvShows.size > 0) {
                                tvMatch = findRes.tvShows.get(0)
                            }
                            
                            var movieMatch: TmdbResult? = null
                            if (findRes.movies != null && findRes.movies.size > 0) {
                                movieMatch = findRes.movies.get(0)
                            }

                            if (isSeries) {
                                if (tvMatch != null) { 
                                    tmdbId = tvMatch.id
                                    actualMediaType = "tv"    
                                    tmdbOverview = tvMatch.overview
                                } else if (movieMatch != null) { 
                                    tmdbId = movieMatch.id
                                    actualMediaType = "movie" 
                                    tmdbOverview = movieMatch.overview
                                }
                            } else {
                                if (movieMatch != null) { 
                                    tmdbId = movieMatch.id
                                    actualMediaType = "movie" 
                                    tmdbOverview = movieMatch.overview
                                } else if (tvMatch != null) { 
                                    tmdbId = tvMatch.id
                                    actualMediaType = "tv"    
                                    tmdbOverview = tvMatch.overview
                                }
                            }
                        }
                    }
                }
            }

            if (tmdbId == null) return TmdbDetails(null, null, null)

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

            TmdbDetails(logoUrl, backdropUrl, tmdbOverview)

        } catch (e: Exception) {
            TmdbDetails(null, null, null)
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
        var url = ""
        
        if (path == "category/anime-series") {
            if (page == 1) {
                url = "$mainUrl/category/anime%20series?type=series"
            } else {
                url = "$mainUrl/category/anime-series?type=series&page=$page"
            }
        } else if (path == "category/anime-movies") {
            if (page == 1) {
                url = "$mainUrl/category/anime%20movies?type=movies"
            } else {
                url = "$mainUrl/category/anime-movies?type=movies&page=$page"
            }
        } else {
            if (page == 1) {
                url = "$mainUrl/$path/"
            } else {
                url = "$mainUrl/$path/?page=$page"
            }
        }

        val document = app.get(url).document
        
        val home = mutableListOf<SearchResponse>()
        val elements = document.select("#movies-a ul > li")
        
        for (el in elements) {
            val res = el.toSearchResult()
            if (res != null) {
                home.add(res)
            }
        }

        return newHomePageResponse(
            list = HomePageList(name = request.name, list = home, isHorizontalImages = false),
            hasNext = home.isNotEmpty()
        )
    }

    private suspend fun fetchFreshDrop(): List<SearchResponse> {
        val document = app.get("$mainUrl/home/").document

        val header = document.select("h3.section-title").firstOrNull { it.text().contains("Fresh Drop", ignoreCase = true) }
        if (header == null) return emptyList()

        val section = header.parents().firstOrNull { it.select("article.post.dfx").isNotEmpty() }
        if (section == null) return emptyList()

        val results = mutableListOf<SearchResponse>()
        val articles = section.select("article.post.dfx")
        
        for (el in articles) {
            val rawTitle = el.selectFirst("h2.entry-title")?.text()?.replace(Regex("(?i)Watch Online"), "")?.trim()
            if (rawTitle.isNullOrBlank()) continue

            val cleanedTitle = cleanTitleText(rawTitle)
            if (cleanedTitle.isBlank()) continue

            val hrefRaw = el.selectFirst("a.lnk-blk")?.attr("href")
            if (hrefRaw.isNullOrBlank()) continue
            val href = fixUrl(hrefRaw)
            
            val posterRaw = el.selectFirst("img")?.attr("src")
            val fallbackPoster = if (posterRaw.isNullOrEmpty()) null else if (posterRaw.startsWith("http")) posterRaw else "https:$posterRaw"
            
            val rating = el.selectFirst("span.vote")?.text()?.replace("TMDB", "")?.trim()?.toDoubleOrNull()
            
            val tmdbAssets = fetchTmdbAssets(null, cleanedTitle, true, null)
            val backdrop = tmdbAssets.backdropUrl ?: fallbackPoster
            
            val mediaJson = Gson().toJson(ToonMedia(href, fallbackPoster))

            val res = newMovieSearchResponse(rawTitle, mediaJson, TvType.TvSeries) {
                this.posterUrl = backdrop
                this.score = Score.from10(rating)
            }
            results.add(res)
        }
        
        return results
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val rawTitle = this.selectFirst("article > header > h2, article h2.entry-title, h2")
            ?.text()?.replace(Regex("(?i)Watch Online"), "")?.trim() ?: return null

        val cleanedTitle = cleanTitleText(rawTitle)
        if (cleanedTitle.isBlank()) return null

        val href  = fixUrl(
            this.selectFirst("article > a.lnk-blk, article a.lnk-blk, a")
                ?.attr("href") ?: return null
        )
        val posterRaw = this.selectFirst("article img, img")?.attr("src") ?: ""
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
        
        val mediaJson = Gson().toJson(ToonMedia(href, poster))

        return newMovieSearchResponse(rawTitle, mediaJson, tvType) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val searchUrl = if (page == 1) {
            "$mainUrl/s?q=$query&type=all"
        } else {
            "$mainUrl/s?q=$query&type=all&page=$page"
        }

        var htmlText = app.get(searchUrl).text
        
        val regex = Regex("(?i)<[^>]+>\\s*(Random Series|Random Movies|Random)\\s*</[^>]+>")
        val match = regex.find(htmlText)
        
        if (match != null) {
            htmlText = htmlText.substring(0, match.range.first)
        }

        val doc = Jsoup.parse(htmlText)
        
        val pageResults = mutableListOf<SearchResponse>()
        var elements = doc.select("#movies-a ul > li")
        
        if (elements.isEmpty()) {
            elements = doc.select("article, .result-item, .item")
        }
        
        if (elements.isEmpty()) {
            elements = doc.select("div:has(h2):has(a):has(img)")
        }
        
        for (el in elements) {
            val res = el.toSearchResult()
            if (res != null) {
                pageResults.add(res)
            }
        }

        return newSearchResponseList(
            list = pageResults,
            hasNext = pageResults.isNotEmpty()
        )
    }

    private fun parseRecommendations(document: Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        try {
            val relatedHeader = document.select("h3").firstOrNull { h ->
                val t = h.text().trim()
                t.equals("Related Series", ignoreCase = true) ||
                t.equals("Related Movies", ignoreCase = true)
            }
            if (relatedHeader == null) return emptyList()

            val relatedSection = relatedHeader.parents().firstOrNull { parent ->
                parent.select(".owl-carousel article.post.dfx").isNotEmpty()
            }
            if (relatedSection == null) return emptyList()

            val articles = relatedSection.select(".owl-carousel article.post.dfx")
            
            for (el in articles) {
                val title = el.selectFirst("h2.entry-title")?.text()?.trim()
                if (title.isNullOrBlank()) continue

                val hrefRaw = el.selectFirst("a.lnk-blk")?.attr("href")
                if (hrefRaw.isNullOrBlank()) continue
                val href = fixUrl(hrefRaw)

                val posterRaw = el.selectFirst("img")?.attr("src") ?: ""
                val poster = when {
                    posterRaw.isEmpty()         -> null
                    posterRaw.startsWith("http") -> posterRaw
                    posterRaw.startsWith("//")   -> "https:$posterRaw"
                    else                         -> posterRaw
                }

                val rating = el.selectFirst("span.vote")?.text()?.replace("TMDB", "")?.trim()?.toDoubleOrNull()

                val tvType = when {
                    href.contains("/series/") -> TvType.TvSeries
                    href.contains("/movies/") -> TvType.Movie
                    else                      -> TvType.Movie
                }
                
                val mediaJson = Gson().toJson(ToonMedia(href, poster))

                val res = newMovieSearchResponse(title, mediaJson, tvType) {
                    this.posterUrl = poster
                    this.score = Score.from10(rating)
                }
                results.add(res)
            }
        } catch (e: Exception) {
            // Ignored
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val media = try {
            Gson().fromJson(url, ToonMedia::class.java)
        } catch (e: Exception) {
            ToonMedia(url, null)
        }
        
        val actualUrl = media.url
        val document   = app.get(actualUrl).document

        val rawTitle   = document.selectFirst("header.entry-header > h1")
            ?.text()?.replace(Regex("(?i)Watch Online"), "")?.trim() ?: ""
        val cleanTitle = cleanTitleText(rawTitle)

        val posterRaw  = document.select("div.bghd > img").attr("src") ?: ""
        val fallbackPoster = if (posterRaw.startsWith("http")) posterRaw else "https:$posterRaw"
        
        val poster = media.poster ?: fallbackPoster
        
        val description = document.selectFirst("div.description > p")?.text()?.trim()
        val isSeries   = actualUrl.contains("/series/")

        val year = document.selectFirst("span.year")?.text()?.trim()?.toIntOrNull()

        val tmdbAssets  = fetchTmdbAssets(document, cleanTitle, isSeries, year)
        val logoUrl     = tmdbAssets.logoUrl
        val backdropUrl = tmdbAssets.backdropUrl

        val finalDescription = if (!tmdbAssets.overview.isNullOrBlank()) {
            tmdbAssets.overview
        } else {
            description
        }

        val displayTitle = rawTitle

        val recommendations = parseRecommendations(document)

        return if (isSeries) {
            loadSeries(
                url, document, displayTitle, poster, finalDescription,
                logoUrl, backdropUrl, year, recommendations
            )
        } else {
            newMovieLoadResponse(displayTitle, url, TvType.Movie, actualUrl) {
                this.posterUrl           = poster
                this.backgroundPosterUrl = backdropUrl ?: poster
                this.plot                = finalDescription
                this.year                = year
                this.logoUrl             = logoUrl
                this.recommendations     = recommendations
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
        year: Int?,
        recommendations: List<SearchResponse> = emptyList()
    ): LoadResponse {
        val episodes = mutableListOf<Episode>()

        val seasonElements = document.select("a.season-btn")
        val seasonNumbers = mutableListOf<Int>()
        for (el in seasonElements) {
            val s = el.attr("data-season").toIntOrNull()
            if (s != null && !seasonNumbers.contains(s)) {
                seasonNumbers.add(s)
            }
        }
        seasonNumbers.sort()
        
        val media = try {
            Gson().fromJson(url, ToonMedia::class.java)
        } catch (e: Exception) {
            ToonMedia(url, null)
        }
        val actualUrl = media.url

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
                Jsoup.parse("")
            }

            val finalDoc = if (seasonDoc.select("article").isEmpty()) {
                try { app.get("$actualUrl/season/$season").document }
                catch (e: Exception) { seasonDoc }
            } else seasonDoc

            var epNum = 1
            val episodeElements = finalDoc.select("article.post.episodes, article.post")
            
            for (ep in episodeElements) {
                val epHref = ep.selectFirst("a.lnk-blk, a")?.attr("href")
                if (epHref.isNullOrBlank()) continue
                
                val epPosterRaw = ep.selectFirst("img")?.attr("src")
                val epPoster = if (epPosterRaw != null) {
                    if (epPosterRaw.startsWith("http")) epPosterRaw else "https:$epPosterRaw"
                } else null
                
                val epName = ep.selectFirst("h5.entry-title1, h2.entry-title, h3.entry-title")?.text()?.trim() ?: "Episode"

                val currentEpisodeNumber = epNum
                epNum += 1

                episodes.add(newEpisode(fixUrl(epHref)) {
                    this.name      = epName
                    this.posterUrl = epPoster
                    this.season    = season
                    this.episode   = currentEpisodeNumber
                })
            }
        }

        if (episodes.isEmpty()) {
            val seasonCounters = mutableMapOf<Int, Int>()
            val backupElements = document.select("#episode_by_temp article.post")
            
            for (ep in backupElements) {
                val epHref = ep.selectFirst("a.lnk-blk, a")?.attr("href")
                if (epHref.isNullOrBlank()) continue
                
                val epPosterRaw = ep.selectFirst("img")?.attr("src")
                val epPoster = if (epPosterRaw != null) {
                    if (epPosterRaw.startsWith("http")) epPosterRaw else "https:$epPosterRaw"
                } else null
                
                val epName = ep.selectFirst("h5.entry-title1")?.text()?.trim() ?: "Episode"
                val numEpi = ep.selectFirst("span.num-epi")?.text()?.trim()
                val epSeason = numEpi?.substringBefore("x")?.toIntOrNull() ?: 1

                val currentCount = seasonCounters[epSeason] ?: 0
                val newCount = currentCount + 1
                seasonCounters[epSeason] = newCount

                episodes.add(newEpisode(fixUrl(epHref)) {
                    this.name      = epName
                    this.posterUrl = epPoster
                    this.season    = epSeason
                    this.episode   = newCount
                })
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl           = poster
            this.backgroundPosterUrl = backdropUrl ?: poster
            this.plot                = description
            this.year                = year
            this.logoUrl             = logoUrl
            this.recommendations     = recommendations
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
                truelink.contains("as-cdn") || truelink.contains("zephyrflick") || truelink.contains("awstream") -> 0
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
        val doc = app.get(url).document
        val m3u8Url = "$mainUrl/player/index.php?data=$extractedHash&do=getVideo"
        val header = mapOf("x-requested-with" to "XMLHttpRequest")
        val formdata = mapOf("hash" to extractedHash, "r" to mainUrl)
        
        val response = app.post(m3u8Url, headers = header, data = formdata).parsedSafe<Response>()
        response?.videoSource?.let { m3u8 ->
            callback.invoke(
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
                Regex("\"kind\":\\s*\"captions\"\\s*,\\s*\"file\":\\s*\"(https.*?\\.srt)\"")
                    .find(unpacked)
                    ?.groupValues
                    ?.get(1)
                    ?.let { subtitleUrl ->
                        subtitleCallback.invoke(
                            SubtitleFile(
                                "English",
                                subtitleUrl
                            )
                        )
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

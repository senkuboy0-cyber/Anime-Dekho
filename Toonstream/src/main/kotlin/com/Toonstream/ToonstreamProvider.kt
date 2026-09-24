package com.Toonstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class ToonstreamProvider : MainAPI() {
    override var mainUrl = "https://toonstream.us"
    override var name = "ToonStream"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/home" to "Fresh Drop",
        "$mainUrl/category/anime?type=all&page=" to "Anime",
        "$mainUrl/category/cartoon?type=all&page=" to "Cartoon",
        "$mainUrl/category/movies?type=all&page=" to "Movies",
        "$mainUrl/category/crunchyroll?type=all&page=" to "Crunchyroll",
        "$mainUrl/category/netflix?type=all&page=" to "Netflix",
    )

    // ─── TMDB ────────────────────────────────────────────────────
    private val TMDB_API = "https://api.themoviedb.org/3"
    private val TMDB_KEY = "1865f43a0549ca50d341dd9ab8b29f49"
    private val TMDB_IMG = "https://image.tmdb.org/t/p/original"

    data class TmdbImages(
        @JsonProperty("logos") val logos: ArrayList<TmdbImage>? = null,
        @JsonProperty("backdrops") val backdrops: ArrayList<TmdbImage>? = null
    )

    data class TmdbImage(
        @JsonProperty("file_path") val filePath: String? = null,
        @JsonProperty("iso_639_1") val lang: String? = null
    )

    data class TmdbSearch(
        @JsonProperty("results") val results: ArrayList<TmdbResult>? = null
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

    data class TmdbDetails(
        val id: Int?,
        val type: String?,
        val logo: String?,
        val backdrop: String?
    )

    data class SearchApiResponse(
        @JsonProperty("count") val count: Int? = null,
        @JsonProperty("data") val data: ArrayList<SearchApiItem>? = null
    )

    data class SearchApiItem(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("url") val url: String? = null
    )

    private fun normalizeTitle(s: String?): String =
        (s ?: "").replace(Regex("[^a-zA-Z0-9]"), "").lowercase()

    private fun getResultYear(result: TmdbResult): Int? {
        val dateString = result.releaseDate ?: result.firstAirDate ?: return null
        if (dateString.contains("-")) return dateString.substringBefore("-").toIntOrNull()
        return null
    }

    private fun yearMatches(tmdbYear: Int?, siteYear: Int?): Boolean {
        if (siteYear == null || tmdbYear == null) return true
        val diff = tmdbYear - siteYear
        return diff == 0 || diff == 1 || diff == -1
    }

    private fun pickBestResult(candidates: List<TmdbResult>, siteYear: Int?): TmdbResult? {
        if (candidates.isEmpty()) return null
        if (siteYear != null) {
            val yearMatched = candidates.filter { yearMatches(getResultYear(it), siteYear) }
            if (yearMatched.isNotEmpty()) {
                if (yearMatched.size == 1) return yearMatched[0]
                return yearMatched.firstOrNull { it.genreIds?.contains(16) == true } ?: yearMatched[0]
            }
        }
        return candidates[0]
    }

    private fun cleanForTmdb(title: String): String {
        var t = title.replace(Regex("Watch Online", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\s+\\d+[x×]\\d+.*"), "")
        t = t.replace(Regex("\\s+Episode\\s+\\d+.*", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\s+Season\\s+\\d+.*", RegexOption.IGNORE_CASE), "")
        t = t.replace(
            Regex(
                "\\s+(?:in\\s+)?(?:hindi|tamil|telugu|english|japanese)\\s*(?:dub(?:bed)?)?\\s*$",
                RegexOption.IGNORE_CASE
            ),
            ""
        )
        t = t.substringBefore("(").substringBefore("[").trim()
        return t.ifBlank { title }
    }

    private suspend fun fetchTmdbAssets(
        document: Document?,
        rawTitle: String,
        isSeries: Boolean,
        year: Int?
    ): TmdbDetails {
        return try {
            val title = cleanForTmdb(rawTitle)
            if (title.isBlank()) return TmdbDetails(null, null, null, null)

            var tmdbId: Int? = null
            var mediaType = if (isSeries) "tv" else "movie"

            val safeTitle = URLEncoder.encode(title, "UTF-8")
            val searchRes = app.get("$TMDB_API/search/multi?api_key=$TMDB_KEY&query=$safeTitle")
                .parsedSafe<TmdbSearch>()

            val validResults = searchRes?.results
                ?.filter { it.mediaType == "movie" || it.mediaType == "tv" }
                .orEmpty()

            val normTitle = normalizeTitle(title)
            val exactCandidates = validResults.filter {
                normalizeTitle(it.title) == normTitle || normalizeTitle(it.name) == normTitle
            }
            val exactMatch = pickBestResult(exactCandidates, year)
            if (exactMatch != null) {
                tmdbId = exactMatch.id
                exactMatch.mediaType?.let { mediaType = it }
            }

            if (tmdbId == null && normTitle.length >= 6) {
                val startsWithCandidates = validResults.filter {
                    val tn = normalizeTitle(it.title).ifEmpty { normalizeTitle(it.name) }
                    tn.startsWith(normTitle)
                }
                val swMatch = pickBestResult(startsWithCandidates, year)
                if (swMatch != null) {
                    tmdbId = swMatch.id
                    swMatch.mediaType?.let { mediaType = it }
                }
            }

            if (tmdbId == null) return TmdbDetails(null, null, null, null)

            val images = app.get("$TMDB_API/$mediaType/$tmdbId/images?api_key=$TMDB_KEY")
                .parsedSafe<TmdbImages>()

            var logoUrl: String? = null
            var backdropUrl: String? = null

            images?.logos?.let { logos ->
                val validLogos = logos.filter { img ->
                    val p = img.filePath ?: ""
                    p.isNotEmpty() && !p.endsWith(".svg", true)
                }
                val bestLogo = validLogos.firstOrNull { it.lang == "en" }
                    ?: validLogos.firstOrNull { it.lang == null }
                    ?: validLogos.firstOrNull { it.lang == "ja" }
                    ?: validLogos.firstOrNull()
                bestLogo?.filePath?.let { logoUrl = "$TMDB_IMG$it" }
            }

            images?.backdrops?.let { backs ->
                val bestBackdrop = backs.filter { it.lang == null }.randomOrNull()
                    ?: backs.filter { it.lang == "en" }.randomOrNull()
                    ?: backs.randomOrNull()
                bestBackdrop?.filePath?.let { backdropUrl = "$TMDB_IMG$it" }
            }

            TmdbDetails(tmdbId, mediaType, logoUrl, backdropUrl)
        } catch (e: Exception) {
            Log.e("ToonStream", "TMDB failed: ${e.message}")
            TmdbDetails(null, null, null, null)
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────

    private fun Element.getImageSrc(): String? {
        val img = this.selectFirst("img") ?: return null
        val src = img.attr("data-src").ifBlank { img.attr("src") }
        if (src.isBlank() || src.contains("TOONSTREAM") || src.contains("sitename")) return null
        return fixUrl(src)
    }

    private fun isMovieUrl(url: String): Boolean =
        url.contains("/movies/")

    private fun isSeriesUrl(url: String): Boolean =
        url.contains("/series/")

    private fun isEpisodeUrl(url: String): Boolean =
        url.contains("/episode/")

    /** Convert episode URL to series URL when possible */
    private fun episodeToSeriesUrl(url: String): String? {
        if (!isEpisodeUrl(url)) return null
        val path = url.substringAfter("/episode/").trim('/')
        val seriesSlug = path.replace(Regex("-\\d+x\\d+/?$"), "")
        if (seriesSlug.isBlank()) return null
        return "$mainUrl/series/$seriesSlug"
    }

    private fun detectType(href: String): TvType = when {
        href.contains("/movies/") -> TvType.Movie
        href.contains("/episode/") -> TvType.TvSeries
        else -> TvType.TvSeries
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a[href]") ?: return null
        var href = a.attr("href").ifBlank { return null }
        if (!href.startsWith("http")) href = fixUrl(href)

        // Prefer series page over episode for home Fresh Drop
        if (isEpisodeUrl(href)) {
            episodeToSeriesUrl(href)?.let { href = it }
        }

        if (!isSeriesUrl(href) && !isMovieUrl(href)) return null

        val title = this.selectFirst(".entry-title, h2, h3")?.text()?.trim()
            ?: a.attr("title").ifBlank { a.text() }.trim()
        if (title.isBlank()) return null

        val poster = this.getImageSrc()
        val type = detectType(href)

        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
    }

    private fun parsePosts(document: Document): List<SearchResponse> {
        val results = ArrayList<SearchResponse>()
        val seen = HashSet<String>()
        document.select(".post.dfx.fcl.movies, .post.dfx, article.post, .post").forEach { el ->
            val item = el.toSearchResult() ?: return@forEach
            if (seen.add(item.url)) results.add(item)
        }
        return results
    }

    private fun parseYear(text: String?): Int? =
        Regex("\\b((?:19|20)\\d{2})\\b").find(text ?: "")?.groupValues?.get(1)?.toIntOrNull()

    // ─── Main page ───────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isHome = request.data.contains("/home")

        if (isHome) {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)

            val document = app.get(request.data).document
            // Prefer posts under Fresh Drop; fallback to all episode-linked posts
            val homeItems = ArrayList<SearchResponse>()
            val seen = HashSet<String>()

            document.select(".post").forEach { el ->
                val href = el.selectFirst("a[href]")?.attr("href") ?: return@forEach
                if (!href.contains("/episode/") && !href.contains("/series/") && !href.contains("/movies/")) {
                    return@forEach
                }
                val item = el.toSearchResult() ?: return@forEach
                if (seen.add(item.url)) homeItems.add(item)
            }

            return newHomePageResponse(
                listOf(HomePageList(request.name, homeItems, isHorizontalImages = false)),
                false
            )
        }

        val url = if (request.data.endsWith("page=")) {
            "${request.data}$page"
        } else if (request.data.contains("page=")) {
            request.data.replace(Regex("page=\\d+"), "page=$page")
        } else {
            "${request.data.trimEnd('/')}?type=all&page=$page"
        }

        val document = app.get(url).document
        val items = parsePosts(document)
        val hasNext = document.select("a.page-link, a[href*=page=]").any {
            it.text().contains("NEXT", true) ||
                (it.text().toIntOrNull() != null && (it.text().toIntOrNull() ?: 0) > page)
        }

        return newHomePageResponse(request.name, items, hasNext)
    }

    // ─── Search ──────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val results = ArrayList<SearchResponse>()
        val seen = HashSet<String>()

        // Live JSON API
        try {
            val api = app.get("\( mainUrl/search/all?q= \){URLEncoder.encode(query, "UTF-8")}")
                .parsedSafe<SearchApiResponse>()
            api?.data?.forEach { item ->
                val path = item.url ?: return@forEach
                val href = if (path.startsWith("http")) path else fixUrl(path)
                if (!seen.add(href)) return@forEach
                val title = item.title ?: return@forEach
                val type = when {
                    item.type.equals("movie", true) || href.contains("/movies/") -> TvType.Movie
                    else -> TvType.TvSeries
                }
                results.add(
                    if (type == TvType.Movie) {
                        newMovieSearchResponse(title, href, TvType.Movie)
                    } else {
                        newTvSeriesSearchResponse(title, href, TvType.TvSeries)
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("ToonStream", "search API failed: ${e.message}")
        }

        if (results.isNotEmpty()) return results

        // HTML fallback
        val document = app.get("\( mainUrl/s?q= \){URLEncoder.encode(query, "UTF-8")}&type=all&page=1").document
        parsePosts(document).forEach {
            if (seen.add(it.url)) results.add(it)
        }
        return results
    }

    // ─── Load ────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val isMovie = isMovieUrl(url)

        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim()
            ?: document.title().substringBefore("-").trim()

        val poster = document.selectFirst("figure img, .poster img")?.let { img ->
            val src = img.attr("data-src").ifBlank { img.attr("src") }
            if (src.contains("TOONSTREAM") || src.contains("sitename")) null else fixUrl(src)
        } ?: document.select("img[src*=tmdb], img[data-src*=tmdb]").firstOrNull()?.let {
            fixUrl(it.attr("data-src").ifBlank { it.attr("src") })
        }

        val plot = document.select("p").map { it.text().trim() }
            .firstOrNull { it.length > 80 }
            ?: document.selectFirst(".description, .overview, .entry-content p")?.text()

        val bodyText = document.body().text()
        val year = parseYear(bodyText)
        val rating = Regex("""([\d.]+)\s*TMDB""", RegexOption.IGNORE_CASE)
            .find(bodyText)?.groupValues?.get(1)?.toDoubleOrNull()

        val actors = document.select("a[href*=cast], .cast a, [class*=cast] a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && it.length < 40 }
            .distinct()
            .take(10)

        val tmdb = fetchTmdbAssets(document, title, !isMovie, year)

        val recommendations = ArrayList<SearchResponse>()
        val recSeen = HashSet<String>()
        document.select("h3.section-title").forEach { h3 ->
            if (!h3.text().contains("Related", true)) return@forEach
            var sibling = h3.parent()?.parent()?.nextElementSibling()
            if (sibling == null) sibling = h3.parent()?.nextElementSibling()
            val scope = sibling ?: document
            scope.select(".post, .owl-carousel .post, article").forEach { el ->
                val item = el.toSearchResult() ?: return@forEach
                if (recSeen.add(item.url)) recommendations.add(item)
            }
        }
        // fallback: any related carousel posts
        if (recommendations.isEmpty()) {
            document.select(".owl-carousel .post").forEach { el ->
                val item = el.toSearchResult() ?: return@forEach
                if (recSeen.add(item.url)) recommendations.add(item)
            }
        }

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = tmdb.backdrop ?: poster
                this.plot = plot
                this.year = year
                this.rating = rating
                this.logoUrl = tmdb.logo
                this.recommendations = recommendations
                if (actors.isNotEmpty()) addActors(actors)
            }
        }

        // Series: collect episodes
        val episodes = ArrayList<Episode>()
        val epSeen = HashSet<String>()

        document.select("a[href*=/episode/]").forEach { a ->
            var href = a.attr("href")
            if (href.isBlank()) return@forEach
            if (!href.startsWith("http")) href = fixUrl(href)
            if (!epSeen.add(href)) return@forEach

            val parent = a.closest(".post, li, article, div") ?: a.parent()
            val epPoster = parent?.selectFirst("img")?.let { img ->
                val src = img.attr("data-src").ifBlank { img.attr("src") }
                if (src.isNotBlank()) fixUrl(src) else null
            }

            val label = a.text().trim().ifBlank {
                parent?.selectFirst(".entry-title1, .entry-title, h5, span")?.text()?.trim()
            } ?: href.substringAfterLast("/").trim('/')

            val seasonEp = Regex("""(\d+)[x×](\d+)""", RegexOption.IGNORE_CASE).find(href)
                ?: Regex("""(\d+)[x×](\d+)""", RegexOption.IGNORE_CASE).find(label)
            val season = seasonEp?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val epNum = seasonEp?.groupValues?.get(2)?.toIntOrNull()
                ?: Regex("""E\s*(\d+)""", RegexOption.IGNORE_CASE).find(label)?.groupValues?.get(1)?.toIntOrNull()
                ?: (episodes.size + 1)

            episodes.add(
                newEpisode(href) {
                    this.name = label.ifBlank { "S$season E$epNum" }
                    this.season = season
                    this.episode = epNum
                    this.posterUrl = epPoster
                }
            )
        }

        episodes.sortWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = tmdb.backdrop ?: poster
            this.plot = plot
            this.year = year
            this.rating = rating
            this.logoUrl = tmdb.logo
            this.recommendations = recommendations
            if (actors.isNotEmpty()) addActors(actors)
        }
    }

    // ─── Links ───────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var found = false

        // iframe data-src / src (skip youtube trailer)
        document.select("iframe[data-src], iframe[src]").forEach { iframe ->
            val src = iframe.attr("data-src").ifBlank { iframe.attr("src") }.trim()
            if (src.isBlank() || src == "about:blank") return@forEach
            if (src.contains("youtube.com", true) || src.contains("youtu.be", true)) return@forEach

            val link = if (src.startsWith("http")) src else fixUrl(src)
            try {
                loadExtractor(link, data, subtitleCallback, callback)
                found = true
            } catch (e: Exception) {
                Log.e("ToonStream", "extractor failed for $link: ${e.message}")
            }
        }

        // also scan any direct embed anchors
        if (!found) {
            document.select("a[href*=embed], a[href*=rubystm], a[href*=vidmoly], a[href*=filesforever], a[href*=abyssplayer], a[href*=emturbovid], a[href*=as-cdn]")
                .forEach { a ->
                    val link = a.attr("href")
                    if (link.isBlank()) return@forEach
                    try {
                        loadExtractor(link, data, subtitleCallback, callback)
                        found = true
                    } catch (_: Exception) {
                    }
                }
        }

        return found
    }
}
package com.myanimes

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
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

data class TmdbDetails(
    val id: Int?,
    val type: String?,
    val logo: String?,
    val backdrop: String?
)

data class AnimeSaltSearchItem(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("image") val image: String? = null,
    @JsonProperty("type") val type: String? = null
)

data class PlayerSource(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("language") val language: String? = null,
    @JsonProperty("server") val server: String? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("direct") val direct: Boolean? = null
)

class MyAnimesProvider : MainAPI() {
    override var mainUrl = "https://myanimes.in"
    override var name = "My Animes"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon,
        TvType.TvSeries,
        TvType.Movie
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Drop",
        "$mainUrl/series/" to "Series",
        "$mainUrl/movies/" to "Movies",
        "$mainUrl/category/crunchyroll/" to "Crunchyroll",
        "$mainUrl/category/ongoing/?type=series" to "On-Air Series"
    )

    private val extAbyss = Abyss()
    private val extStreamP2P = StreamP2P()
    private val extCloudy = Cloudy()

    private val TMDB_API = "https://api.themoviedb.org/3"
    private val TMDB_KEY = "1865f43a0549ca50d341dd9ab8b29f49"
    private val TMDB_IMG = "https://image.tmdb.org/t/p/original"
    private val normalizeRegex = Regex("[^a-zA-Z0-9]")
    private val searchApi = "$mainUrl/wp-json/animesalt/v1/search"

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

    private fun cleanTitleForTmdb(title: String): String {
        return title.replace(Regex("(?i)\\s+Season\\s+\\d+.*"), "")
            .replace(Regex("(?i)\\s+Episode\\s+\\d+.*"), "")
            .substringBefore("(")
            .substringBefore("[")
            .trim()
    }

    private suspend fun fetchTmdbDetails(
        document: org.jsoup.nodes.Document,
        title: String,
        isSeries: Boolean,
        year: Int?
    ): TmdbDetails {
        return try {
            val safeTitle = encodeUri(title)
            val searchRes = app.get("$TMDB_API/search/multi?api_key=$TMDB_KEY&query=$safeTitle")
                .parsedSafe<TmdbSearch>()

            val validResults = searchRes?.results?.filter {
                it.mediaType == "movie" || it.mediaType == "tv"
            } ?: emptyList()
            val normTitle = normalizeTitle(title)

            val exactCandidates = validResults.filter {
                normalizeTitle(it.title ?: it.name) == normTitle
            }
            var bestMatch = pickBestResult(exactCandidates, year)

            if (bestMatch == null && normTitle.length >= 6) {
                val startsWithCandidates = validResults.filter {
                    val tn = normalizeTitle(it.title ?: it.name)
                    tn.isNotEmpty() && tn.startsWith(normTitle)
                }
                bestMatch = pickBestResult(startsWithCandidates, year)
            }

            var tmdbId = bestMatch?.id
            var actualMediaType = bestMatch?.mediaType ?: if (isSeries) "tv" else "movie"

            if (tmdbId == null) {
                val imdbId = document.select("a[href*=imdb.com/title]").mapNotNull { link ->
                    val possibleId = link.attr("href").substringAfter("title/").substringBefore("/")
                    if (possibleId.startsWith("tt")) possibleId else null
                }.firstOrNull()

                if (imdbId != null) {
                    val findRes = app.get(
                        "$TMDB_API/find/$imdbId?api_key=$TMDB_KEY&external_source=imdb_id"
                    ).parsedSafe<TmdbFind>()
                    val match = if (isSeries) {
                        findRes?.tvShows?.firstOrNull() ?: findRes?.movies?.firstOrNull()
                    } else {
                        findRes?.movies?.firstOrNull() ?: findRes?.tvShows?.firstOrNull()
                    }
                    if (match != null) {
                        tmdbId = match.id
                        actualMediaType = match.mediaType ?: actualMediaType
                    }
                }
            }

            if (tmdbId == null && validResults.isNotEmpty()) {
                val fallback = pickBestResult(validResults, year)
                tmdbId = fallback?.id
                actualMediaType = fallback?.mediaType ?: actualMediaType
            }

            if (tmdbId == null) return TmdbDetails(null, null, null, null)

            val images = app.get(
                "$TMDB_API/$actualMediaType/$tmdbId/images?api_key=$TMDB_KEY"
            ).parsedSafe<TmdbImages>()

            var logoUrl: String? = null
            var backdropUrl: String? = null

            if (images != null) {
                val validLogos = images.logos?.filter {
                    it.filePath?.endsWith(".svg", ignoreCase = true) != true
                } ?: emptyList()
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
            Log.e("MyAnimes", "TMDB failed: ${e.message}")
            TmdbDetails(null, null, null, null)
        }
    }

    // --- Main Page ---

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isHome = request.data == "$mainUrl/" || request.data == mainUrl

        if (isHome) {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
            val document = app.get(mainUrl).document
            val home = document.select("section.as-latest-drop article.as-card")
                .mapNotNull { it.toSearchResult() }
            return newHomePageResponse(request.name, home, false)
        }

        val url = if (page <= 1) {
            request.data
        } else {
            val base = request.data.trimEnd('/')
            when {
                base.contains("/page/") -> base.replace(Regex("/page/\\d+"), "/page/$page")
                base.contains("?") -> {
                    val path = base.substringBefore("?")
                    val query = base.substringAfter("?")
                    "$path/page/$page/?$query"
                }
                else -> "$base/page/$page/"
            }
        }

        val document = app.get(url).document
        val home = document.select("article.as-card").mapNotNull { it.toSearchResult() }
        val hasNext = document.selectFirst("nav.as-pagination a.next.page-numbers") != null
                || document.selectFirst("nav.as-pagination a.page-numbers[href*=/page/${page + 1}]") != null
                || document.selectFirst("link[rel=next]") != null

        return newHomePageResponse(request.name, home, hasNext)
    }

    // --- Search ---

    override suspend fun search(query: String): List<SearchResponse> {
        try {
            val apiRes = app.get("$searchApi?q=${encodeUri(query)}")
                .parsedSafe<ArrayList<AnimeSaltSearchItem>>()
            if (!apiRes.isNullOrEmpty()) {
                return apiRes.mapNotNull { item ->
                    val href = item.url ?: return@mapNotNull null
                    val title = item.title ?: return@mapNotNull null
                    val poster = item.image
                    val isMovie = href.contains("/movies/") || item.type.equals("movie", true)
                    if (isMovie) {
                        newMovieSearchResponse(title, href, TvType.AnimeMovie) {
                            this.posterUrl = poster
                        }
                    } else {
                        newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                            this.posterUrl = poster
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MyAnimes", "Search API failed: ${e.message}")
        }

        val document = app.get("$mainUrl/?s=${encodeUri(query)}").document
        return document.select("article.as-card").mapNotNull { it.toSearchResult() }
    }

    // --- Details / Load ---

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val isMovie = url.contains("/movies/")

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("h1 a")?.text()?.trim()
            ?: return null

        // Fix: Prioritize only actual vertical posters instead of hero/backdrop images
        val poster = document.selectFirst(".as-poster img, .post-thumbnail img")?.let {
            it.attr("src").ifBlank { it.attr("data-src") }
        } ?: document.selectFirst("img[src*=w500], img[src*=w300]")?.attr("src")
          ?: document.selectFirst(".as-hero img, img[src*=storyblok], img[src*=image.tmdb.org]")?.attr("src")

        val plot = document.selectFirst(
            ".as-overview, .overview, .description, .entry-content p, section.single p"
        )?.text()?.trim()

        val year = document.selectFirst("span.year, .as-meta")?.text()?.let {
            Regex("""\b(19|20)\d{2}\b""").find(it)?.value?.toIntOrNull()
        } ?: Regex("""\b(19|20)\d{2}\b""").find(document.text())?.value?.toIntOrNull()

        val tags = document.select("a[href*=/category/]")
            .map { it.text().trim() }
            .filter {
                it.isNotBlank()
                        && !it.equals("Watch Now", true)
                        && !it.equals("View More", true)
            }
            .distinct()

        val actors = document.select("li.rw")
            .firstOrNull { it.selectFirst("span")?.text()?.contains("Cast", true) == true }
            ?.select("a")?.map { it.text().trim() }.orEmpty()

        val recommendations = document.select("article.as-card")
            .mapNotNull { it.toSearchResult() }
            .filter { it.url != url }

        val tmdbTitle = cleanTitleForTmdb(title)
        val tmdbDetails = fetchTmdbDetails(document, tmdbTitle, !isMovie, year)

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = tmdbDetails.backdrop ?: poster
                this.logoUrl = tmdbDetails.logo
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
                addActors(actors)
            }
        }

        val episodes = mutableListOf<Episode>()

        document.select("div.as-episode-grid").forEach { grid ->
            val seasonFromPanel = grid.attr("data-season-panel").toIntOrNull()
                ?: grid.id().substringAfter("season-", "").toIntOrNull()
                ?: 1

            grid.select("a.as-episode[href*=/episode/]").forEach { a ->
                val href = fixUrl(a.attr("href"))
                val seMatch = Regex("""(\d+)x(\d+)""").find(href)
                val seasonNum = seMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: seasonFromPanel
                val epNum = seMatch?.groupValues?.getOrNull(2)?.toIntOrNull()
                    ?: a.selectFirst("b")?.text()?.trim()?.toIntOrNull()
                    ?: 0

                val epName = a.selectFirst("h3")?.text()?.trim()?.ifBlank { null }
                    ?: "Episode $epNum"

                val epPoster = a.selectFirst("img")?.attr("src")?.ifBlank {
                    a.selectFirst("img")?.attr("data-src")
                }

                episodes.add(newEpisode(href) {
                    this.name = epName
                    this.season = seasonNum
                    this.episode = epNum
                    this.posterUrl = epPoster
                })
            }
        }

        if (episodes.isEmpty()) {
            document.select("a.as-episode[href*=/episode/], a[href*=/episode/]").forEach { a ->
                val href = fixUrl(a.attr("href"))
                val seMatch = Regex("""(\d+)x(\d+)""").find(href)
                val seasonNum = seMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                val epNum = seMatch?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
                val epName = a.selectFirst("h3")?.text()?.trim()?.ifBlank { null } ?: "Episode $epNum"
                val epPoster = a.selectFirst("img")?.attr("src")

                episodes.add(newEpisode(href) {
                    this.name = epName
                    this.season = seasonNum
                    this.episode = epNum
                    this.posterUrl = epPoster
                })
            }
        }

        val uniqueEpisodes = episodes.distinctBy { it.data }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, uniqueEpisodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = tmdbDetails.backdrop ?: poster
            this.logoUrl = tmdbDetails.logo
            this.year = year
            this.plot = plot
            this.tags = tags
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    // --- Load Links ---

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var found = false

        val sourcesJson = document.selectFirst("section.as-player")?.attr("data-sources")
        val sources = try {
            if (!sourcesJson.isNullOrBlank()) {
                AppUtils.parseJson<List<PlayerSource>>(sourcesJson)
            } else emptyList()
        } catch (e: Exception) {
            Log.e("MyAnimes", "data-sources parse failed: ${e.message}")
            emptyList()
        }

        val embedUrls = LinkedHashSet<String>()

        sources.forEach { src ->
            src.url?.takeIf { it.isNotBlank() }?.let { embedUrls.add(fixUrl(it)) }
        }

        if (embedUrls.isEmpty()) {
            document.select(".as-player-screen iframe[src], iframe[src*=hydrax], iframe[src*=streamp2p], iframe[src*=php/]")
                .forEach { iframe ->
                    val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                    if (src.isNotBlank()) embedUrls.add(fixUrl(src))
                }
        }

        for (embedUrl in embedUrls) {
            try {
                val lower = embedUrl.lowercase()
                when {
                    lower.contains("hydrax.php") -> {
                        if (resolveHydraxAllLangs(embedUrl, subtitleCallback, callback)) {
                            found = true
                        }
                    }
                    lower.contains("streamp2p.php") -> {
                        val playerSrc = resolveWrapperIframe(embedUrl) ?: continue
                        extStreamP2P.getUrl(playerSrc, mainUrl, subtitleCallback, callback)
                        found = true
                    }
                    lower.contains("upns") || lower.contains("cloudy") || lower.contains("p2pplay") -> {
                        extCloudy.getUrl(embedUrl, mainUrl, subtitleCallback, callback)
                        found = true
                    }
                    lower.contains("abyssplayer") || lower.contains("hydrax") -> {
                        extAbyss.getUrl(embedUrl, mainUrl, subtitleCallback, callback)
                        found = true
                    }
                    else -> {
                        val playerSrc = resolveWrapperIframe(embedUrl) ?: embedUrl
                        val pLower = playerSrc.lowercase()
                        when {
                            pLower.contains("abyssplayer") || pLower.contains("hydrax") -> {
                                extAbyss.getUrl(playerSrc, mainUrl, subtitleCallback, callback)
                                found = true
                            }
                            pLower.contains("p2pplay") || pLower.contains("upns") || pLower.contains("cloudy") -> {
                                if (pLower.contains("p2pplay") || (pLower.contains("#") && pLower.contains("play"))) {
                                    extStreamP2P.getUrl(playerSrc, mainUrl, subtitleCallback, callback)
                                } else {
                                    extCloudy.getUrl(playerSrc, mainUrl, subtitleCallback, callback)
                                }
                                found = true
                            }
                            else -> {
                                if (loadExtractor(playerSrc, mainUrl, subtitleCallback, callback)) {
                                    found = true
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MyAnimes", "loadLinks error: ${e.message}")
            }
        }

        return found
    }

    /**
     * hydrax.php contains multi-language Abyss tracks.
     * Extract every language URL and run Abyss on each.
     */
    private suspend fun resolveHydraxAllLangs(
        hydraxUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var any = false
        try {
            val doc = app.get(hydraxUrl, referer = mainUrl).document
            val html = doc.html()

            // tracks = {"Hindi":"https://player.abyssplayer.com/...","Tamil":"...",...}
            val tracksBlock = Regex(
                """tracks\s*=\s*(\{[^}]+\})""",
                RegexOption.IGNORE_CASE
            ).find(html)?.groupValues?.getOrNull(1)

            val trackUrls = linkedMapOf<String, String>()

            if (!tracksBlock.isNullOrBlank()) {
                Regex(""""([^"]+)"\s*:\s*"(https?://[^"]+)"""")
                    .findAll(tracksBlock)
                    .forEach { m ->
                        val lang = m.groupValues[1]
                        val url = m.groupValues[2]
                        trackUrls[lang] = url
                    }
            }

            // Fallback: single iframe
            if (trackUrls.isEmpty()) {
                val iframeSrc = doc.selectFirst("iframe[src]")?.attr("src")?.trim()
                if (!iframeSrc.isNullOrBlank()) {
                    trackUrls["Default"] = fixUrl(iframeSrc)
                }
            }

            for ((lang, playerUrl) in trackUrls) {
                try {
                    // Pass language via a temporary name trick: Abyss uses its own name,
                    // so we wrap callback to tag the language.
                    val taggedCallback: (ExtractorLink) -> Unit = { link ->
                        callback(
                            ExtractorLink(
                                source = link.source,
                                name = if (lang.equals("Default", true)) link.name else "${link.name} [$lang]",
                                url = link.url,
                                referer = link.referer,
                                quality = link.quality,
                                type = link.type,
                                headers = link.headers
                            )
                        )
                    }
                    extAbyss.getUrl(playerUrl, mainUrl, subtitleCallback, taggedCallback)
                    any = true
                } catch (e: Exception) {
                    Log.e("MyAnimes", "Abyss track $lang failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("MyAnimes", "resolveHydraxAllLangs failed: ${e.message}")
        }
        return any
    }

    private suspend fun resolveWrapperIframe(wrapperUrl: String): String? {
        return try {
            if (!wrapperUrl.contains(".php/")) return wrapperUrl
            val doc = app.get(wrapperUrl, referer = mainUrl).document
            val iframe = doc.selectFirst("iframe[src]")?.attr("src")?.trim()
            if (!iframe.isNullOrBlank()) fixUrl(iframe) else null
        } catch (e: Exception) {
            Log.e("MyAnimes", "resolveWrapperIframe failed: ${e.message}")
            null
        }
    }

    // --- Helpers ---

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = this.selectFirst("a.as-card-link[href], a[href*=/series/], a[href*=/movies/]")
            ?: return null
        val href = fixUrl(anchor.attr("href"))

        if (!href.contains("/series/") && !href.contains("/movies/")) return null

        val title = this.selectFirst("h3, h2.entry-title, .entry-title")?.text()?.trim()
            ?: this.selectFirst("img[alt]")?.attr("alt")?.trim()
            ?: anchor.attr("aria-label")?.trim()
            ?: return null

        val poster = this.selectFirst(".as-poster img, figure img, img")?.let {
            it.attr("src").ifBlank { it.attr("data-src") }
        }

        return if (href.contains("/movies/")) {
            newMovieSearchResponse(title, href, TvType.AnimeMovie) {
                this.posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
    }
}

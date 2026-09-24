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
import java.nio.charset.StandardCharsets

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

    data class SearchApiResponse(
        @JsonProperty("count") val count: Int? = null,
        @JsonProperty("data") val data: ArrayList<SearchApiItem>? = null
    )

    data class SearchApiItem(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("url") val url: String? = null
    )

    private fun Element.getImageSrc(): String? {
        val img = this.selectFirst("img") ?: return null
        val src = img.attr("data-src").ifBlank { img.attr("src") }
        if (src.isBlank() || src.contains("TOONSTREAM") || src.contains("sitename")) return null
        return fixUrl(src)
    }

    private fun isMovieUrl(url: String): Boolean = url.contains("/movies/")

    private fun isSeriesUrl(url: String): Boolean = url.contains("/series/")

    private fun isEpisodeUrl(url: String): Boolean = url.contains("/episode/")

    private fun episodeToSeriesUrl(url: String): String? {
        if (!isEpisodeUrl(url)) return null
        val path = url.substringAfter("/episode/").trim('/')
        val seriesSlug = path.replace(Regex("""-\d+x\d+/?$"""), "")
        if (seriesSlug.isBlank()) return null
        return "$mainUrl/series/$seriesSlug"
    }

    private fun detectType(href: String): TvType = when {
        href.contains("/movies/") -> TvType.Movie
        else -> TvType.TvSeries
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a[href]") ?: return null
        var href = a.attr("href").ifBlank { return null }
        if (!href.startsWith("http")) href = fixUrl(href)

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
        Regex("""\b((?:19|20)\d{2})\b""").find(text ?: "")?.groupValues?.get(1)?.toIntOrNull()

    private fun encodeQuery(query: String): String =
        URLEncoder.encode(query, StandardCharsets.UTF_8.toString())

    // ─── Main page ───────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isHome = request.data.contains("/home")

        if (isHome) {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)

            val document = app.get(request.data).document
            val homeItems = ArrayList<SearchResponse>()
            val seen = HashSet<String>()

            document.select(".post").forEach { el ->
                val href = el.selectFirst("a[href]")?.attr("href") ?: return@forEach
                if (!href.contains("/episode/") &&
                    !href.contains("/series/") &&
                    !href.contains("/movies/")
                ) {
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

        val url = when {
            request.data.endsWith("page=") -> "${request.data}$page"
            request.data.contains("page=") ->
                request.data.replace(Regex("""page=\d+"""), "page=$page")
            else -> "${request.data.trimEnd('/')}?type=all&page=$page"
        }

        val document = app.get(url).document
        val items = parsePosts(document)
        val hasNext = document.select("a.page-link, a[href*=page=]").any {
            it.text().contains("NEXT", true) ||
                ((it.text().toIntOrNull() ?: 0) > page)
        }

        return newHomePageResponse(request.name, items, hasNext)
    }

    // ─── Search ──────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val results = ArrayList<SearchResponse>()
        val seen = HashSet<String>()
        val encodedQuery = encodeQuery(query)

        try {
            val api = app.get("$mainUrl/search/all?q=$encodedQuery")
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

        val document = app.get("$mainUrl/s?q=$encodedQuery&type=all&page=1").document
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
        val score = Regex("""([\d.]+)\s*TMDB""", RegexOption.IGNORE_CASE)
            .find(bodyText)?.groupValues?.get(1)?.toDoubleOrNull()
            ?.times(1000)?.toInt()

        val actors = document.select("a[href*=cast], .cast a, [class*=cast] a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && it.length < 40 }
            .distinct()
            .take(10)

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

        if (recommendations.isEmpty()) {
            document.select(".owl-carousel .post").forEach { el ->
                val item = el.toSearchResult() ?: return@forEach
                if (recSeen.add(item.url)) recommendations.add(item)
            }
        }

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.plot = plot
                this.year = year
                this.score = score
                this.recommendations = recommendations
                if (actors.isNotEmpty()) addActors(actors)
            }
        }

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
                ?: Regex("""E\s*(\d+)""", RegexOption.IGNORE_CASE).find(label)
                    ?.groupValues?.get(1)?.toIntOrNull()
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
            this.backgroundPosterUrl = poster
            this.plot = plot
            this.year = year
            this.score = score
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

        if (!found) {
            document.select(
                "a[href*=embed], a[href*=rubystm], a[href*=vidmoly], " +
                    "a[href*=filesforever], a[href*=abyssplayer], " +
                    "a[href*=emturbovid], a[href*=as-cdn]"
            ).forEach { a ->
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
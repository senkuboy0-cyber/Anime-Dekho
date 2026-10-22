package com.hianimeq

import com.google.gson.Gson
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Base64

data class HianimeqMedia(
    val url: String,
    val poster: String? = null,
    val title: String? = null
)

class HianimeqProvider : MainAPI() {
    override var mainUrl = "https://hianimeb.de"
    override var name = "Hianimeq"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie
    )

    override val mainPage = mainPageOf(
        "recently-updated" to "Recently Updated",
        "genre/action" to "Action",
        "dubbed-anime" to "Dubbed Anime",
        "genre/ecchi" to "Ecchi",
        "genre/harem" to "Harem",
        "genre/isekai" to "Isekai",
        "genre/romance" to "Romance"
    )

    private fun encodeUri(text: String): String {
        return try {
            URLEncoder.encode(text, "UTF-8")
        } catch (_: Exception) {
            text
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.trim('/')
        val url = if (page <= 1) {
            "$mainUrl/$path/"
        } else {
            "$mainUrl/$path/?anime-page=$page"
        }

        val document = app.get(url).document
        val home = document.select("div.flw-item").mapNotNull { it.toSearchResult() }
        val hasNext = document.select("ul.pagination a.page-link[href*=anime-page]").any {
            val n = it.text().trim().toIntOrNull()
            n != null && n > page
                    || it.attr("title").equals("Next", true)
                    || it.attr("title").equals("Last", true)
        }

        return newHomePageResponse(request.name, home, hasNext)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encoded = encodeUri(query)
        val url = if (page <= 1) {
            "$mainUrl/?s=$encoded"
        } else {
            "$mainUrl/?s=$encoded&anime-page=$page"
        }
        val document = app.get(url).document
        val results = document.select("div.flw-item").mapNotNull { it.toSearchResult() }
        val hasNext = document.select("ul.pagination a.page-link[href*=anime-page]").any {
            it.attr("title").equals("Next", true)
                    || (it.text().trim().toIntOrNull()?.let { n -> n > page } == true)
        }
        return newSearchResponseList(results, hasNext)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("h3.film-name a.dynamic-name")
            ?: selectFirst("a.film-poster-ahref")
            ?: return null

        val title = anchor.attr("title").ifBlank { anchor.text() }.trim()
        if (title.isBlank()) return null

        val href = fixUrl(anchor.attr("href"))
        if (href.isBlank()) return null

        val poster = selectFirst("img.film-poster-img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }?.replace("&#038;", "&")

        val typeText = selectFirst(".fd-infor .fdi-item")?.text()?.trim().orEmpty()
        val isMovie = typeText.contains("Movie", true)
                || href.contains("-movie", true)
                || href.contains("/movie", true)

        val media = Gson().toJson(HianimeqMedia(href, poster, title))

        return if (isMovie) {
            newAnimeSearchResponse(title, media, TvType.AnimeMovie) {
                this.posterUrl = poster
            }
        } else {
            newAnimeSearchResponse(title, media, TvType.Anime) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val media = try {
            if (url.trim().startsWith("{")) {
                Gson().fromJson(url, HianimeqMedia::class.java)
            } else {
                HianimeqMedia(url)
            }
        } catch (_: Exception) {
            HianimeqMedia(url)
        }

        val playUrl = media.url
        val playDoc = app.get(playUrl).document

        val detailUrl = playDoc.selectFirst(".anisc-detail h2.film-name a")?.attr("href")
            ?.let { fixUrl(it) }
            ?: playUrl.toAnimeDetailUrl()

        val detailDoc = if (detailUrl != playUrl) {
            runCatching { app.get(detailUrl).document }.getOrNull() ?: playDoc
        } else {
            playDoc
        }

        val title = media.title
            ?: detailDoc.selectFirst(".anisc-detail h2.film-name a, h2.film-name a, h1")?.text()?.trim()
            ?: playDoc.selectFirst(".anisc-detail h2.film-name a")?.text()?.trim()
            ?: "Unknown"

        val poster = media.poster
            ?: playDoc.selectFirst(".anisc-poster img.film-poster-img")?.attr("src")
                ?.replace("&#038;", "&")
            ?: detailDoc.selectFirst(".film-poster-img, .anisc-poster img")?.attr("src")
                ?.replace("&#038;", "&")

        val background = playDoc.selectFirst(".anis-cover")?.attr("style")
            ?.let { Regex("""url\(([^)]+)\)""").find(it)?.groupValues?.get(1) }
            ?.replace("&#038;", "&")
            ?.trim('"', '\'')
            ?: poster

        val plot = detailDoc.selectFirst(".film-description .text")?.text()?.trim()
            ?: playDoc.selectFirst(".film-description .text")?.text()?.trim()

        val year = run {
            val airedText = detailDoc.selectFirst(".item:has(.item-title:contains(Aired)), .anisc-info")
                ?.text().orEmpty()
            Regex("""((?:19|20)\d{2})""").find(airedText)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""Aired:\s*[A-Za-z]+\s+\d{1,2},\s*((?:19|20)\d{2})""")
                    .find(detailDoc.text())?.groupValues?.get(1)?.toIntOrNull()
        }

        val recommendations = (detailDoc.select("section.block_area_home div.flw-item")
            .ifEmpty { playDoc.select("section.block_area_home div.flw-item") })
            .mapNotNull { it.toSearchResult() }

        val animeId = playDoc.selectFirst("#ani_detail")?.attr("data-anime-id")
            ?: detailDoc.selectFirst("#ani_detail, [data-anime-id]")?.attr("data-anime-id")

        val typeText = playDoc.selectFirst(".prebreadcrumb .breadcrumb-item a[href*='/type/']")
            ?.text()?.trim().orEmpty()
        val isMovie = typeText.contains("Movie", true)
                || playUrl.contains("-movie", true)

        if (isMovie) {
            val episodeId = playDoc.selectFirst("#ani_detail")?.attr("data-id")
                ?: playDoc.selectFirst("a.ep-item.active, a.ep-item")?.attr("data-id")
            val data = Gson().toJson(
                mapOf(
                    "playUrl" to playUrl,
                    "episodeId" to episodeId
                )
            )
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, data) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
                this.year = year
                this.tags = emptyList()
                this.actors = emptyList()
                this.recommendations = recommendations
            }
        }

        val episodes = mutableListOf<Episode>()
        if (!animeId.isNullOrBlank()) {
            val api = "$mainUrl/wp-json/v1/otakuthemes/episode/list/$animeId"
            val body = app.get(api).text
            val html = runCatching {
                org.json.JSONObject(body).optString("html")
            }.getOrNull().orEmpty()

            val epDoc = Jsoup.parse(html.ifBlank { body })
            epDoc.select("a.ep-item").forEach { ep ->
                val epUrl = fixUrl(ep.attr("href"))
                val epId = ep.attr("data-id")
                val number = ep.attr("data-number").toIntOrNull()
                    ?: ep.selectFirst(".ssli-order")?.text()?.toIntOrNull()
                val epName = ep.selectFirst(".ep-name")?.text()?.trim()
                    ?: ep.attr("title").ifBlank { "Episode $number" }

                if (epUrl.isNotBlank()) {
                    episodes.add(
                        newEpisode(
                            Gson().toJson(
                                mapOf(
                                    "playUrl" to epUrl,
                                    "episodeId" to epId
                                )
                            )
                        ) {
                            this.name = epName
                            this.episode = number
                        }
                    )
                }
            }
        }

        if (episodes.isEmpty()) {
            val epId = playDoc.selectFirst("#ani_detail")?.attr("data-id")
            episodes.add(
                newEpisode(
                    Gson().toJson(
                        mapOf(
                            "playUrl" to playUrl,
                            "episodeId" to epId
                        )
                    )
                ) {
                    this.name = "Episode 1"
                    this.episode = 1
                }
            )
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.backgroundPosterUrl = background
            this.plot = plot
            this.year = year
            this.tags = emptyList()
            this.actors = emptyList()
            this.recommendations = recommendations
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val map = try {
            @Suppress("UNCHECKED_CAST")
            Gson().fromJson(data, Map::class.java) as Map<String, String?>
        } catch (_: Exception) {
            mapOf("playUrl" to data, "episodeId" to null)
        }

        var episodeId = map["episodeId"]
        val playUrl = map["playUrl"] ?: data

        if (episodeId.isNullOrBlank()) {
            val doc = app.get(playUrl).document
            episodeId = doc.selectFirst("#ani_detail")?.attr("data-id")
                ?: doc.selectFirst("a.ep-item.active")?.attr("data-id")
        }
        if (episodeId.isNullOrBlank()) return false

        val serversApi = "$mainUrl/wp-json/v1/otakuthemes/episode/servers?episodeId=$episodeId"
        val response = app.get(serversApi).text
        val html = runCatching {
            org.json.JSONObject(response).optString("html")
        }.getOrNull().orEmpty()

        val serverDoc = Jsoup.parse(html.ifBlank { response })
        val servers = serverDoc.select(".server-item[data-hash]")

        var found = false
        for (server in servers) {
            val hash = server.attr("data-hash")
            if (hash.isBlank()) continue
            val embedUrl = try {
                String(Base64.getDecoder().decode(hash), Charsets.UTF_8)
            } catch (_: Exception) {
                continue
            }
            if (embedUrl.startsWith("http")) {
                loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)
                found = true
            }
        }
        return found
    }

    private fun String.toAnimeDetailUrl(): String {
        val path = substringAfter(mainUrl).trim('/')
        val slug = path
            .replace(Regex("-episode-\\d+/?" + "$"), "")
            .trim('/')
        return if (slug.isNotBlank()) "$mainUrl/anime/$slug/" else this
    }

    private fun fixUrl(url: String): String {
        if (url.isBlank()) return url
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> mainUrl + url
            else -> "$mainUrl/$url"
        }
    }
}

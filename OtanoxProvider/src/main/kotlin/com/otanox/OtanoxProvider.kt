package com.otanox

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class OtanoxProvider : MainAPI() {
    override var mainUrl = "https://www.otanox.online"
    override var name = "OtaNox"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val consumet = "$mainUrl/api/consumet/meta/anilist"

    override val mainPage = mainPageOf(
        "$consumet/trending?perPage=24" to "Trending",
        "$consumet/popular?perPage=24" to "Popular",
        "$consumet/advanced-search?status=RELEASING&sort=POPULARITY_DESC&perPage=24" to "Airing",
        "$consumet/advanced-search?format=TV&sort=SCORE_DESC&perPage=24" to "Top Rated",
        "$consumet/advanced-search?format=MOVIE&sort=POPULARITY_DESC&perPage=24" to "Movies",
        "$consumet/recent-episodes?perPage=24" to "Recent Episodes"
    )

    private fun enc(text: String): String {
        return try {
            URLEncoder.encode(text, "UTF-8")
        } catch (_: Exception) {
            text
        }
    }

    private fun JSONObject.titleText(): String {
        val t = optJSONObject("title")
        return t?.optString("english")?.takeIf { it.isNotBlank() }
            ?: t?.optString("userPreferred")?.takeIf { it.isNotBlank() }
            ?: t?.optString("romaji")?.takeIf { it.isNotBlank() }
            ?: optString("title").takeIf { it.isNotBlank() }
            ?: "Unknown"
    }

    private fun JSONObject.toSearchResponse(): SearchResponse? {
        val id = optString("id").ifBlank {
            opt("id")?.toString().orEmpty()
        }
        if (id.isBlank() || id.startsWith("ak_")) return null

        val title = titleText()
        val poster = optString("image").ifBlank { null }
        val typeStr = optString("type").uppercase()
        val isMovie = typeStr == "MOVIE" || typeStr == "SPECIAL"

        return newAnimeSearchResponse(
            title,
            id,
            if (isMovie) TvType.AnimeMovie else TvType.Anime
        ) {
            this.posterUrl = poster
        }
    }

    private fun parseResults(body: String): List<SearchResponse> {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val arr: JSONArray = when {
            root.has("results") -> root.optJSONArray("results")
            root.has("data") -> root.optJSONArray("data")
            else -> null
        } ?: return emptyList()

        val out = mutableListOf<SearchResponse>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.toSearchResponse()?.let { out.add(it) }
        }
        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data
        val sep = if (base.contains("?")) "&" else "?"
        val url = "\( base \){sep}page=$page"
        val body = app.get(url).text
        val home = parseResults(body)
        val hasNext = home.size >= 20
        return newHomePageResponse(request.name, home, hasNext)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = "\( mainUrl/api/search?q= \){enc(query)}&limit=30"
        val body = app.get(url).text
        val results = parseResults(body)
        return newSearchResponseList(results, false)
    }

    override suspend fun load(url: String): LoadResponse {
        val id = url.substringAfterLast("/").substringBefore("?").ifBlank { url }

        val infoBody = app.get("$consumet/info/$id").text
        val info = JSONObject(infoBody)

        val title = info.titleText()
        val poster = info.optString("image").ifBlank { null }
        val background = info.optString("cover").ifBlank { poster }
        val plot = info.optString("description")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { null }
        val year = info.optJSONObject("startDate")?.optInt("year")
            ?.takeIf { it > 0 }
            ?: info.optInt("releaseDate").takeIf { it > 1900 }
        val genres = mutableListOf<String>()
        info.optJSONArray("genres")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotBlank() }?.let { genres.add(it) }
            }
        }
        val typeStr = info.optString("type").uppercase()
        val isMovie = typeStr == "MOVIE"

        val recommendations = mutableListOf<SearchResponse>()
        runCatching {
            val details = JSONObject(app.get("$mainUrl/api/anilist/details/$id").text)
            details.optJSONObject("recommendations")?.optJSONArray("nodes")?.let { nodes ->
                for (i in 0 until nodes.length()) {
                    val media = nodes.optJSONObject(i)?.optJSONObject("mediaRecommendation") ?: continue
                    val recId = media.opt("id")?.toString() ?: continue
                    val recTitle = media.optJSONObject("title")?.let { t ->
                        t.optString("english").ifBlank { t.optString("romaji") }
                    } ?: continue
                    val recPoster = media.optJSONObject("coverImage")?.optString("large")
                    recommendations.add(
                        newAnimeSearchResponse(recTitle, recId, TvType.Anime) {
                            this.posterUrl = recPoster
                        }
                    )
                }
            }
            details.optJSONObject("relations")?.optJSONArray("edges")?.let { edges ->
                for (i in 0 until edges.length()) {
                    val edge = edges.optJSONObject(i) ?: continue
                    val node = edge.optJSONObject("node") ?: continue
                    if (!node.optString("type").equals("ANIME", true)) continue
                    val relId = node.opt("id")?.toString() ?: continue
                    val relTitle = node.optJSONObject("title")?.let { t ->
                        t.optString("english").ifBlank { t.optString("romaji") }
                    } ?: continue
                    val relPoster = node.optJSONObject("coverImage")?.optString("large")
                    recommendations.add(
                        newAnimeSearchResponse(relTitle, relId, TvType.Anime) {
                            this.posterUrl = relPoster
                        }
                    )
                }
            }
        }

        val epMeta = mutableMapOf<Int, Pair<String?, String?>>()
        runCatching {
            val zip = JSONObject(app.get("https://api.ani.zip/mappings?anilist_id=$id").text)
            val episodesObj = zip.optJSONObject("episodes") ?: return@runCatching
            val keys = episodesObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val num = key.toIntOrNull() ?: continue
                val ep = episodesObj.optJSONObject(key) ?: continue
                val epTitle = ep.optJSONObject("title")?.optString("en")
                    ?.ifBlank { ep.optJSONObject("title")?.optString("ja") }
                val thumb = ep.optString("image").ifBlank { null }
                epMeta[num] = epTitle to thumb
            }
        }

        val epBody = app.get("$mainUrl/api/provider/anikoto2/episodes?anilistId=$id").text
        val epRoot = JSONObject(epBody)
        val epArr = epRoot.optJSONArray("episodes") ?: JSONArray()

        val episodes = mutableListOf<Episode>()
        for (i in 0 until epArr.length()) {
            val ep = epArr.optJSONObject(i) ?: continue
            val number = ep.optInt("number")
            if (number <= 0) continue
            val serverIds = ep.optString("serverIds")
            val hasSub = ep.optBoolean("hasSub", true)
            val hasDub = ep.optBoolean("hasDub", false)
            val fallbackName = ep.optString("title").ifBlank { "Episode $number" }
            val meta = epMeta[number]
            val epName = meta?.first?.ifBlank { null } ?: fallbackName
            val epPoster = meta?.second

            val data = JSONObject()
                .put("id", id)
                .put("ep", number)
                .put("serverIds", serverIds)
                .put("hasSub", hasSub)
                .put("hasDub", hasDub)
                .toString()

            episodes.add(
                newEpisode(data) {
                    this.name = epName
                    this.episode = number
                    this.posterUrl = epPoster
                }
            )
        }

        val recs = recommendations.distinctBy { it.url }

        if (isMovie || episodes.size <= 1) {
            val data = if (episodes.isNotEmpty()) {
                episodes.first().data
            } else {
                JSONObject()
                    .put("id", id)
                    .put("ep", 1)
                    .put("serverIds", "")
                    .put("hasSub", true)
                    .put("hasDub", false)
                    .toString()
            }
            return newMovieLoadResponse(title, id, TvType.AnimeMovie, data) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
                this.year = year
                this.tags = genres
                this.recommendations = recs
            }
        }

        val hasAnyDub = episodes.any {
            runCatching { JSONObject(it.data).optBoolean("hasDub") }.getOrDefault(false)
        }

        return newAnimeLoadResponse(title, id, TvType.Anime) {
            this.posterUrl = poster
            this.backgroundPosterUrl = background
            this.plot = plot
            this.year = year
            this.tags = genres
            this.recommendations = recs
            addEpisodes(DubStatus.Subbed, episodes)
            if (hasAnyDub) {
                addEpisodes(DubStatus.Dubbed, episodes)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val json = runCatching { JSONObject(data) }.getOrNull() ?: return false
        val id = json.optString("id")
        val ep = json.optInt("ep", 1)
        val serverIds = json.optString("serverIds")
        val hasSub = json.optBoolean("hasSub", true)
        val hasDub = json.optBoolean("hasDub", false)

        var found = false

        suspend fun sourcesFromAnikoto(type: String) {
            if (serverIds.isBlank()) return
            val servers = listOf("HD-1", "HD-2", "Vidstream-2", "Vidstream-1 beta")
            val urls = mutableListOf(
                "\( mainUrl/api/provider/anikoto2/sources?serverIds= \){enc(serverIds)}&type=$type"
            )
            servers.forEach { s ->
                urls.add(
                    "\( mainUrl/api/provider/anikoto2/sources?serverIds= \){enc(serverIds)}&type=\( type&server= \){enc(s)}"
                )
            }
            val seen = mutableSetOf<String>()
            for (api in urls) {
                val body = runCatching { app.get(api).text }.getOrNull() ?: continue
                val obj = runCatching { JSONObject(body) }.getOrNull() ?: continue
                val embed = obj.optString("url")
                if (embed.isBlank()) continue
                if (!seen.add(embed)) continue
                if (loadExtractor(embed, mainUrl, subtitleCallback, callback)) {
                    found = true
                }
            }
        }

        if (hasSub) sourcesFromAnikoto("sub")
        if (hasDub) sourcesFromAnikoto("dub")

        if (id.isNotBlank()) {
            val reBody = runCatching {
                app.get("$mainUrl/api/provider/reanime/servers?id=$id&ep=$ep").text
            }.getOrNull()
            if (!reBody.isNullOrBlank()) {
                val re = runCatching { JSONObject(reBody) }.getOrNull()
                listOf("sub", "dub").forEach { key ->
                    val arr = re?.optJSONArray(key) ?: return@forEach
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val link = item.optString("dataLink")
                        if (link.isBlank()) continue
                        if (loadExtractor(link, mainUrl, subtitleCallback, callback)) {
                            found = true
                        }
                    }
                }
            }
        }

        return found
    }
}
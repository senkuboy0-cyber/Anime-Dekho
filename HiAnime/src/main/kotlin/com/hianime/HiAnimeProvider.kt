package com.hianime

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import java.util.Base64

class HiAnimeProvider : MainAPI() {
    override var mainUrl = "https://hianime.at"
    override var name = "HiAnime"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val ajaxServers = "$mainUrl/api/theme/episode/servers?episodeId="
    private val ajaxSources = "$mainUrl/api/theme/episode/sources?id="
    private val ajaxEpisodes = "$mainUrl/api/theme/episode/list/"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val html = app.get("$mainUrl/home").text
        val document = Jsoup.parse(html)
        val homePageList = ArrayList<HomePageList>()

        document.select("section.block_area").forEach { block ->
            val title = block.selectFirst("h2.cat-heading")?.text()?.trim() ?: return@forEach
            val elements = block.select("div.flw-item").mapNotNull {
                it.toSearchResult()
            }
            if (elements.isNotEmpty()) {
                homePageList.add(HomePageList(title, elements))
            }
        }
        return HomePageResponse(homePageList)
    }

    private fun org.jsoup.nodes.Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3.film-name > a, a.dynamic-name")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val url = if (href.startsWith("http")) href else "$mainUrl$href"
        val poster = this.selectFirst("img")?.attr("data-src")?.ifBlank { this.selectFirst("img")?.attr("src") }
        
        return newAnimeSearchResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            val epText = this@toSearchResult.selectFirst("div.tick-eps")?.text()?.trim()
            if (epText != null) {
                val epNum = epText.replace(Regex("[^0-9]"), "").toIntOrNull()
                if (epNum != null) addSub(epNum)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?keyword=$query"
        val html = app.get(url).text
        val document = Jsoup.parse(html)
        return document.select("div.flw-item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val html = app.get(url).text
        val document = Jsoup.parse(html)

        val title = document.selectFirst("h2.film-name")?.text()?.trim() 
            ?: document.selectFirst("ol.breadcrumb li.active")?.text()?.trim() ?: ""
        
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content") 
            ?: document.selectFirst("img.film-poster-img")?.attr("src")

        val description = document.selectFirst("div.film-description")?.text()?.trim()

        var year: Int? = null
        document.select("div.item").forEach { item ->
            val text = item.text().trim()
            if (text.contains("Aired:") || text.contains("Premiered:") || text.contains("Release:")) {
                // Ensure we get the very first year mentioned (ignoring the "to 2026" part)
                val yearRegex = Regex("""(20\d{2}|19\d{2})""")
                val match = yearRegex.find(text)
                if (match != null && year == null) {
                    year = match.value.toIntOrNull()
                }
            }
        }

        val recommendations = document.select("section#anime-related div.flw-item, section#anime-trending div.flw-item").mapNotNull {
            it.toSearchResult()
        }

        val episodes = ArrayList<Episode>()
        
        val animeIdMatch = Regex("""data-id=["'](\d+)["']""").find(html)?.groupValues?.get(1) 
            ?: Regex("""id="syncData"[^>]*data-anime-id=["'](\d+)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""anime_id\s*=\s*['"]?(\d+)['"]?""").find(html)?.groupValues?.get(1)
        
        if (animeIdMatch != null) {
            val epListUrl = "$ajaxEpisodes$animeIdMatch"
            val epRes = app.get(epListUrl, headers = mapOf("X-Requested-With" to "XMLHttpRequest")).parsedSafe<HtmlResponse>()
            if (epRes?.html != null) {
                val epDoc = Jsoup.parse(epRes.html)
                epDoc.select("a.ep-item").forEach { epItem ->
                    val epId = epItem.attr("data-id")
                    val epNum = epItem.attr("data-number").toIntOrNull()
                    val epTitle = epItem.attr("title").ifBlank { epItem.attr("data-jname") }
                    
                    episodes.add(Episode(
                        data = epId,
                        name = epTitle,
                        episode = epNum,
                        posterUrl = poster
                    ))
                }
            }
        }
        
        if (episodes.isEmpty()) {
            val epId = Regex("""\?ep=(\d+)""").find(url)?.groupValues?.get(1)
            if (epId != null) {
                episodes.add(Episode(
                    data = epId,
                    name = title,
                    episode = 1,
                    posterUrl = poster
                ))
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.recommendations = recommendations
            addEpisodes(Episode.EpisodeType.SUB, episodes) 
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val serversUrl = "$ajaxServers$data"
        val res = app.get(serversUrl, headers = mapOf("X-Requested-With" to "XMLHttpRequest")).parsedSafe<HtmlResponse>()
        val html = res?.html ?: return false

        val document = Jsoup.parse(html)
        var foundLinks = false

        document.select("div.server-item").forEach { server ->
            val serverId = server.attr("data-id")
            val dataHash = server.attr("data-hash")
            
            // Method 1: Decode Iframe directly from base64 data-hash
            if (dataHash.isNotBlank()) {
                try {
                    val decodedUrl = String(Base64.getDecoder().decode(dataHash))
                    loadExtractor(decodedUrl, subtitleCallback, callback)
                    foundLinks = true
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } 
            // Method 2: Use API fallback to get Iframe link
            else if (serverId.isNotBlank()) {
                val sourcesUrl = "$ajaxSources$serverId"
                val srcRes = app.get(sourcesUrl, headers = mapOf("X-Requested-With" to "XMLHttpRequest")).parsedSafe<SourceResponse>()
                val link = srcRes?.link
                if (!link.isNullOrBlank()) {
                    loadExtractor(link, subtitleCallback, callback)
                    foundLinks = true
                }
            }
        }
        
        return foundLinks
    }

    data class HtmlResponse(
        @JsonProperty("status") val status: Boolean? = null,
        @JsonProperty("html") val html: String? = null
    )

    data class SourceResponse(
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("link") val link: String? = null
    )
}

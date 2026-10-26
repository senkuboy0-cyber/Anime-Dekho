package com.hianime

import com.fasterxml.jackson.annotation.JsonProperty

// ---------------------------------------------------------------------------
// hianime.at JSON endpoints
// ---------------------------------------------------------------------------

// /api/theme/episode/list/{animeId}      -> html with a.ep-item
// /api/theme/episode/servers?episodeId=  -> html with .server-item
data class HtmlResponse(
    val status: Boolean? = null,
    val success: Boolean? = null,
    val html: String? = null,
)

// ---------------------------------------------------------------------------
// api.ani.zip (by MAL id, season specific)
// ---------------------------------------------------------------------------

data class AniZip(
    val episodeCount: Int? = null,
    val episodes: Map<String, AniZipEpisode>? = null,
    val images: List<AniZipImage>? = null,
    val mappings: AniZipMappings? = null,
)

data class AniZipEpisode(
    val title: Map<String, String?>? = null,
    val overview: String? = null,
    val summary: String? = null,
    val image: String? = null,
    val airDate: String? = null,
    val airdate: String? = null,
    val runtime: Int? = null,
)

data class AniZipImage(val coverType: String? = null, val url: String? = null)

data class AniZipMappings(
    @JsonProperty("themoviedb_id") val themoviedbId: Any? = null,
    @JsonProperty("thetvdb_id") val thetvdbId: Int? = null,
    @JsonProperty("imdb_id") val imdbId: String? = null,
    @JsonProperty("mal_id") val malId: Int? = null,
)

// ---------------------------------------------------------------------------
// TMDB
// ---------------------------------------------------------------------------

data class TmdbImages(
    val logos: List<TmdbImage>? = null,
    val backdrops: List<TmdbImage>? = null,
)

data class TmdbImage(
    @JsonProperty("file_path") val filePath: String? = null,
    @JsonProperty("iso_639_1") val iso6391: String? = null,
)

data class TmdbFind(
    @JsonProperty("tv_results") val tvResults: List<TmdbId>? = null,
    @JsonProperty("movie_results") val movieResults: List<TmdbId>? = null,
)

data class TmdbSearch(val results: List<TmdbId>? = null)

data class TmdbId(val id: Int? = null)

// ---------------------------------------------------------------------------
// Data passed from load() to loadLinks() for one episode (or the movie).
// ---------------------------------------------------------------------------
data class EpisodeData(
    val episodeId: String,
    val number: Int,
)

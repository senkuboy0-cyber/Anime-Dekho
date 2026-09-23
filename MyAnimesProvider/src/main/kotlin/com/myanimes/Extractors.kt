package com.myanimes

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Abyss / Hydrax player used by myanimes.in (hydrax.php -> player.abyssplayer.com)
 * Decrypts via enc-dec.app API.
 */
class Abyss : ExtractorApi() {
    override var name = "Abyss"
    override var mainUrl = "https://player.abyssplayer.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Origin" to "https://playhydrax.com",
            "Referer" to "https://playhydrax.com/"
        )

        val document = app.get(url, headers = headers).document
        val scripts = document.select("script").joinToString("\n") { it.data() }

        val encrypted = Regex("""const\s+datas\s*=\s*"([^"]*)"""")
            .find(scripts)?.groupValues?.getOrNull(1) ?: return

        val decrypted = app.post(
            url = "https://enc-dec.app/api/dec-abyss",
            headers = headers,
            requestBody = """{"text":"$encrypted"}"""
                .trimIndent()
                .toRequestBody("application/json".toMediaType())
        ).parsedSafe<AbyssResponse>()?.result ?: return

        decrypted.sources
            .filter { it.status }
            .forEach { source ->
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name [${source.codec.uppercase()}]",
                        url = source.url,
                        type = INFER_TYPE
                    ) {
                        this.quality = getQualityFromName(source.type)
                        this.headers = mapOf(
                            "Referer" to "https://player.abyssplayer.com/",
                            "Origin" to "https://player.abyssplayer.com"
                        )
                    }
                )
            }
    }

    data class AbyssResponse(val status: Long, val result: Result)
    data class Result(val sources: List<AbyssSource>)
    data class AbyssSource(
        val url: String,
        val size: Long,
        val type: String,
        val codec: String,
        val status: Boolean,
    )
}

/**
 * StreamP2P wrapper used by myanimes.in (streamp2p.php -> *.p2pplay.online/#hash)
 */
class StreamP2P : UpnsPlayer() {
    override var name = "StreamP2P"
    override var mainUrl = "https://hindianimezone.p2pplay.online"
}

/**
 * Upns / Cloudy family – AES-CBC encrypted API response.
 */
class Cloudy : UpnsPlayer() {
    override var name = "Cloudy"
    override var mainUrl = "https://cloudy.upns.one"
}

open class UpnsPlayer : ExtractorApi() {
    override var name = "Upns"
    override var mainUrl = "https://upns.one"
    override val requiresReferer = true

    companion object {
        private const val AES_KEY = "kiemtienmua911ca"
        private const val AES_IV = "1234567890oiuytr"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val baseurl = getBaseUrl(url)
        val hash = url.substringAfterLast("#").substringBefore("&").substringBefore("?")
            .ifBlank { url.trimEnd('/').substringAfterLast('/') }
        if (hash.isBlank()) return

        val refHost = try {
            URI(referer ?: mainUrl).host ?: mainUrl.removePrefix("https://")
        } catch (_: Exception) {
            mainUrl.removePrefix("https://")
        }

        val encoded = try {
            app.get(
                "$baseurl/api/v1/video?id=$hash&w=1280&h=720&r=$refHost",
                headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "*/*"),
                referer = referer ?: "$baseurl/"
            ).text.trim()
        } catch (e: Exception) {
            Log.e(name, "API failed: ${e.message}")
            return
        }
        if (encoded.isBlank() || encoded.startsWith("{")) return

        val decryptedJson = decryptHex(encoded) ?: run {
            Log.e(name, "AES decrypt failed")
            return
        }

        val obj = try {
            JSONObject(decryptedJson)
        } catch (e: Exception) {
            Log.e(name, "JSON parse failed: ${e.message}")
            return
        }

        val finalUrl = buildFromStreamingConfig(obj)
            ?: buildFromAbsolutePath(obj)
            ?: run {
                Log.e(name, "No playable URL in response")
                return
            }

        callback(
            newExtractorLink(name, name, url = finalUrl, type = ExtractorLinkType.M3U8) {
                this.referer = "$baseurl/"
                this.quality = Qualities.Unknown.value
            }
        )

        val subs = obj.optJSONObject("subtitle")
        subs?.keys()?.forEach { lang ->
            val rawPath = subs.optString(lang).split("#").firstOrNull().orEmpty()
            if (rawPath.isNotBlank()) {
                val subUrl = if (rawPath.startsWith("http")) rawPath else "$baseurl$rawPath"
                subtitleCallback(SubtitleFile(lang.uppercase(), subUrl))
            }
        }
    }

    private fun buildFromStreamingConfig(obj: JSONObject): String? {
        return try {
            var videoPath = obj.optString("source").takeIf { it.isNotEmpty() && !it.startsWith("http") }
                ?: obj.optString("hls").takeIf { it.isNotEmpty() && !it.startsWith("http") }
                ?: obj.optString("hlsVideoTiktok").takeIf { it.isNotEmpty() && !it.startsWith("http") }
                ?: return null

            val cfgRaw = obj.optJSONObject("streamingConfig")?.toString()
                ?: obj.optString("streamingConfig").takeIf { it.isNotBlank() }
                ?: return null

            val cfg = JSONObject(cfgRaw)
            val adjust = cfg.optJSONObject("adjust") ?: return null
            val order = cfg.optJSONArray("order")

            val candidates = mutableListOf<JSONObject>()
            if (order != null) {
                for (i in 0 until order.length()) {
                    adjust.optJSONObject(order.getString(i))?.let { candidates.add(it) }
                }
            } else {
                adjust.keys().forEach { k -> adjust.optJSONObject(k)?.let { candidates.add(it) } }
            }

            for (c in candidates) {
                if (c.optBoolean("disabled", false)) continue
                val rawDomain = c.optString("domain").takeIf { it.isNotBlank() } ?: continue
                val cleanDomain = rawDomain.removePrefix("https://").removePrefix("http://").trimEnd('/')
                val cleanPath = if (videoPath.startsWith("/")) videoPath else "/$videoPath"
                val sb = StringBuilder("https://").append(cleanDomain).append(cleanPath)
                val params = c.optJSONObject("params")
                if (params != null && params.length() > 0) {
                    sb.append("?")
                    val keys = params.keys()
                    var first = true
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (!first) sb.append("&")
                        sb.append(k).append("=").append(params.optString(k))
                        first = false
                    }
                }
                return sb.toString()
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun buildFromAbsolutePath(obj: JSONObject): String? {
        val candidates = listOf(
            obj.optString("hlsVideoTiktok"),
            obj.optString("source"),
            obj.optString("hls")
        )
        return candidates.firstOrNull { it.startsWith("http") }
    }

    private fun decryptHex(hex: String): String? {
        return try {
            val clean = hex.trim().removeSurrounding("\"")
            val data = clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(AES_KEY.toByteArray(), "AES"),
                IvParameterSpec(AES_IV.toByteArray())
            )
            String(cipher.doFinal(data))
        } catch (_: Exception) {
            null
        }
    }

    protected fun getBaseUrl(url: String): String =
        try {
            URI(url).let { "${it.scheme}://${it.host}" }
        } catch (_: Exception) {
            mainUrl
        }
}

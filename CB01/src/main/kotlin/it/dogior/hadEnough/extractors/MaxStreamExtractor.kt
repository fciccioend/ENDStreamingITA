package it.dogior.hadEnough.extractors

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

class MaxStreamExtractor : ExtractorApi() {
    override var name = "MaxStream"
    override var mainUrl = "https://maxsun435.online"
    override val requiresReferer = true  // CAMBIATO A true!

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        Log.d("MaxStreamExtractor", "🔍 Inizio estrazione per: $url")
        
        try {
            // HEADERS migliorati per bypassare CloudFlare
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Accept-Language" to "it-IT,it;q=0.9,en;q=0.8",
                "Accept-Encoding" to "gzip, deflate, br",
                "Referer" to referer ?: "https://cb01uno.uno",
                "DNT" to "1",
                "Connection" to "keep-alive",
                "Upgrade-Insecure-Requests" to "1",
                "Sec-Fetch-Dest" to "document",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "cross-site"
            )
            
            Log.d("MaxStreamExtractor", "📄 Scarico pagina: $url")
            val response = app.get(url, headers = headers, timeout = 30000)
            val html = response.body.string()
            
            Log.d("MaxStreamExtractor", "✅ Pagina scaricata (${html.length} chars)")
            
            // STRATEGIA 1: Cerca sorgenti video direttamente nell'HTML
            Log.d("MaxStreamExtractor", "🔍 Cercando sorgenti video...")
            
            // Pattern per video MP4/M3U8
            val videoPatterns = listOf(
                Regex("""["']file["']\s*:\s*["']([^"']+)["']"""),
                Regex("""["']url["']\s*:\s*["']([^"']+)["']"""),
                Regex("""["']src["']\s*:\s*["']([^"']+)["']"""),
                Regex("""sources\s*:\s*\[(.*?)\]""", RegexOption.DOTALL),
                Regex("""(https?://[^\s"']+\.(?:mp4|m3u8|mkv)[^\s"']*)""", RegexOption.IGNORE_CASE)
            )
            
            for ((index, pattern) in videoPatterns.withIndex()) {
                Log.d("MaxStreamExtractor", "  Pattern $index: ${pattern.pattern.take(50)}...")
                val matches = pattern.findAll(html).toList()
                
                if (matches.isNotEmpty()) {
                    Log.d("MaxStreamExtractor", "  ✅ Trovati ${matches.size} match")
                    
                    matches.forEach { match ->
                        val videoUrl = when (pattern) {
                            videoPatterns[3] -> { // sources array
                                val sourcesText = match.groupValues[1]
                                val fileMatch = Regex("""["']file["']\s*:\s*["']([^"']+)["']""").find(sourcesText)
                                fileMatch?.groupValues?.get(1)
                            }
                            else -> match.groupValues.getOrNull(1) ?: match.value
                        }
                        
                        if (!videoUrl.isNullOrEmpty() && videoUrl.contains("http")) {
                            Log.d("MaxStreamExtractor", "  🎬 Video URL trovato: ${videoUrl.take(100)}...")
                            
                            val isM3u8 = videoUrl.contains(".m3u8") || videoUrl.contains("/hls/")
                            val type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = "$name (${if (isM3u8) "HLS" else "Direct"})",
                                    url = videoUrl,
                                    type = type
                                ) {
                                    this.referer = url
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            return
                        }
                    }
                }
            }
            
            // STRATEGIA 2: Prova endpoint API (se l'URL ha struttura complessa)
            if (url.contains("/watchfree/") && url.split("/").size >= 6) {
                val parts = url.split("/")
                val id1 = parts[parts.size - 3]
                val id2 = parts[parts.size - 2]
                val hash = parts.last()
                
                Log.d("MaxStreamExtractor", "🔑 ID trovati: id1=$id1, id2=$id2, hash=$hash")
                
                val apiUrls = listOf(
                    "$mainUrl/api/player?key1=$id1&key2=$id2&hash=$hash",
                    "$mainUrl/embed/$id1/$id2/$hash",
                    "$mainUrl/stream/$id1/$id2/$hash"
                )
                
                for (apiUrl in apiUrls) {
                    try {
                        Log.d("MaxStreamExtractor", "🔗 Provo API: $apiUrl")
                        val apiResponse = app.get(apiUrl, headers = headers, timeout = 15000)
                        val apiText = apiResponse.body.string()
                        
                        // Cerca video in risposta API
                        val apiVideoUrl = Regex("""["']url["']\s*:\s*["']([^"']+)["']""").find(apiText)?.groupValues?.get(1)
                        
                        if (!apiVideoUrl.isNullOrEmpty()) {
                            Log.d("MaxStreamExtractor", "✅ Video da API: $apiVideoUrl")
                            
                            val isM3u8 = apiVideoUrl.contains(".m3u8")
                            val type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = "$name (API)",
                                    url = apiVideoUrl,
                                    type = type
                                ) {
                                    this.referer = url
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            return
                        }
                    } catch (e: Exception) {
                        Log.d("MaxStreamExtractor", "⚠️ API fallita: ${e.message}")
                    }
                }
            }
            
            // STRATEGIA 3: Cerca iframe
            val iframePattern = Regex("""<iframe[^>]+src=["']([^"']+)["']""")
            val iframeMatch = iframePattern.find(html)
            
            if (iframeMatch != null) {
                val iframeUrl = iframeMatch.groupValues[1]
                Log.d("MaxStreamExtractor", "🔄 Trovato iframe: $iframeUrl")
                
                // Se l'iframe punta a un video, usalo direttamente
                if (iframeUrl.contains(".mp4") || iframeUrl.contains(".m3u8")) {
                    val isM3u8 = iframeUrl.contains(".m3u8")
                    val type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name (iframe)",
                            url = iframeUrl,
                            type = type
                        ) {
                            this.referer = url
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return
                }
            }
            
            Log.e("MaxStreamExtractor", "❌ Nessun video trovato nell'HTML")
            Log.d("MaxStreamExtractor", "📝 Anteprima HTML (primi 2000 chars):")
            Log.d("MaxStreamExtractor", html.take(2000))
            
        } catch (e: Exception) {
            Log.e("MaxStreamExtractor", "💥 Errore durante estrazione: ${e.message}", e)
        }
    }
}

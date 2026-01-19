package it.dogior.hadEnough

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SeasonData
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.addPoster
import com.lagradost.cloudstream3.addSeasonNames
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ShortLink
import it.dogior.hadEnough.extractors.MaxStreamExtractor
import it.dogior.hadEnough.extractors.MixDropExtractor
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class CB01 : MainAPI() {
    override var mainUrl = "https://cb01uno.uno"
    override var name = "CB01"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Cartoon)
    override var lang = "it"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        mainUrl to "Film",
        "$mainUrl/serietv" to "Serie TV"
    )

    companion object {
        var actualMainUrl = ""
    }

    private fun fixTitle(title: String, isMovie: Boolean): String {
        if (isMovie) {
            return title.replace(Regex("""(\[HD] )*\(\d{4}\)${'$'}"""), "")
        }
        return title.replace(Regex("""[-–] Stagione \d+"""), "")
            .replace(Regex("""[-–] ITA"""), "")
            .replace(Regex("""[-–] *\d+[x×]\d*(/?\d*)*"""), "")
            .replace(Regex("""[-–] COMPLETA"""), "").trim()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}/page/$page/" else request.data
        val response = app.get(url)

        if (actualMainUrl.isEmpty()) {
            actualMainUrl = response.okhttpResponse.request.url.toString().substringBeforeLast('/')
        }

        val document = response.document
        val items = document.selectFirst(".sequex-one-columns")!!.select(".post")
        val posts = items.mapNotNull { card ->
            val poster = card.selectFirst("img")?.attr("src")
            val data = card.selectFirst("script")?.data()
            val fixedData = data?.substringAfter("=")?.substringBefore(";")
            val post = tryParseJson<Post>(fixedData)
            post?.let { it.poster = poster }
            post
        }
        val pagination = document.selectFirst(".pagination")?.select(".page-item")!!
        val lastPage = pagination[pagination.size - 2].text().replace(".", "").toInt()
        val hasNext = page < lastPage

        val searchResponses = posts.map {
            if (request.data.contains("serietv")) {
                val title = fixTitle(it.title, false)
                newTvSeriesSearchResponse(title, it.permalink, TvType.TvSeries) {
                    addPoster(it.poster)
                }
            } else {
                val quality = if (it.title.contains("HD")) SearchQuality.HD else null
                newMovieSearchResponse(
                    fixTitle(it.title, true),
                    it.permalink,
                    TvType.Movie
                ) {
                    addPoster(it.poster)
                    this.quality = quality
                }
            }
        }
        val section = HomePageList(request.name, searchResponses, false)
        return newHomePageResponse(section, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchLinks =
            listOf("$mainUrl/?s=$query", "$mainUrl/serietv/?s=$query")
        val results = searchLinks.amap { link ->
            val response = app.get(link)
            val document = response.document
            val itemColumn = document.selectFirst(".sequex-one-columns")
            val items = itemColumn?.select(".post")
            val posts = items?.mapNotNull { card ->
                val poster = card.selectFirst("img")?.attr("src")
                val data = card.selectFirst("script")?.data()
                val fixedData = data?.substringAfter("=")?.substringBefore(";")
                val post = tryParseJson<Post>(fixedData)
                post?.let { it.poster = poster }
                post
            }
            posts?.map {
                if (link.contains("serietv")) {
                    newTvSeriesSearchResponse(
                        fixTitle(it.title, false),
                        it.permalink,
                        TvType.TvSeries
                    ) {
                        addPoster(it.poster)
                    }
                } else {
                    val quality = if (it.title.contains("HD")) SearchQuality.HD else null
                    newMovieSearchResponse(
                        fixTitle(it.title, true),
                        it.permalink,
                        TvType.Movie
                    ) {
                        addPoster(it.poster)
                        this.quality = quality
                    }
                }
            }
        }.filterNotNull().flatten()
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val urlPath = url.substringAfter("//").substringAfter('/')
        if (actualMainUrl.isEmpty()) {
            val r = app.get(url)
            actualMainUrl = r.okhttpResponse.request.url.toString().substringBeforeLast('/')
        }
        val actualUrl = "$actualMainUrl/$urlPath"
        Log.d("CB01:load", url)

        val document =
            app.get(actualUrl, headers = mapOf("Host" to actualUrl.toHttpUrl().host)).document
        val mainContainer = document.selectFirst(".sequex-main-container")!!
        val poster =
            mainContainer.selectFirst("img.responsive-locandina")?.attr("src")
        val banner = mainContainer.selectFirst("#sequex-page-title-img")?.attr("data-img")
        val title = mainContainer.selectFirst("h1")?.text()!!
        val isMovie = !actualUrl.contains("serietv")
        val type = if (isMovie) TvType.Movie else TvType.TvSeries
        return if (isMovie) {
            val year = Regex("\\d{4}").find(title)?.value?.toIntOrNull()
            val plot = mainContainer.selectFirst(".ignore-css > p:nth-child(2)")?.text()
                ?.replace("+Info »", "")
            val tags =
                mainContainer.selectFirst(".ignore-css > p:nth-child(1) > strong:nth-child(1)")
                    ?.text()?.split('–')
            val runtime = tags?.find { it.contains("DURATA") }?.trim()
                ?.removePrefix("DURATA")
                ?.removeSuffix("′")?.trim()?.toInt()

            val table =
                mainContainer.selectFirst("table.cbtable > tbody:nth-child(1) > tr:nth-child(1) > td:nth-child(1)")
            val links = table?.select("a")?.mapNotNull {
                if (it.text() == "Maxstream" || it.text() == "Mixdrop") {
                    it.attr("href")
                } else {
                    null
                }
            }
            val data = links?.let {
                it.subList(links.size - 2, it.size)
            }?.toJson() ?: "null"

            newMovieLoadResponse(fixTitle(title, true), actualUrl, type, data) {
                addPoster(poster)
                this.plot = plot
                this.backgroundPosterUrl = banner
                this.tags = tags?.mapNotNull {
                    if (it.contains("DURATA")) null else it.trim()
                }
                this.duration = runtime
                this.year = year
            }
        } else {
            val description = mainContainer.selectFirst(".ignore-css > p:nth-child(1)")?.text()
                ?.split(Regex("""\(\d{4}-(\d{4})?\)"""))
            val plot = description?.last()?.trim()
            val tags = description?.first()?.split('/')
            val (episodes, seasons) = getEpisodes(document)
            newTvSeriesLoadResponse(fixTitle(title, false), actualUrl, type, episodes) {
                addPoster(poster)
                addSeasonNames(seasons)
                this.plot = plot
                this.backgroundPosterUrl = banner
                this.tags = tags?.map { it.trim() }
            }
        }
    }

    private suspend fun getEpisodes(page: Document): Pair<List<Episode>, MutableList<SeasonData>> {
        val seasonsData = mutableListOf<SeasonData>()
        val nestedEps = mutableListOf<Episode>()

        val seasonDropdowns = page.select(".sp-wrap ")

        val episodes = seasonDropdowns.mapIndexedNotNull { index, dropdown ->
            // Every Season
            val seasonName = dropdown.select("div.sp-head").text()
            val regex = "\\d+".toRegex()
            val seasonNumber = regex.find(seasonName)?.value?.toIntOrNull() ?: index

            val episodesData = dropdown.select("div.sp-body").select("strong").select("p")
            episodesData.amap {
                // Every episode
                val epName = it.text().substringBefore('–').trim()
                val epNumber = regex.find(epName.substringAfter('×'))?.value?.toIntOrNull()
                val links = it.select("a").map { a -> a.attr("href") }
                if (links.any { l -> l.contains("uprot") || l.contains("stayonline") }) {
                    seasonsData.add(
                        SeasonData(
                            seasonNumber,
                            seasonName.replace("- ITA", "")
                                .replace("- HD", "").trim()
                        )
                    )
                    val uprotLink = try {
                        links.first { l -> l.contains("uprot") }
                    } catch (e: NoSuchElementException) {
                        null
                    }
                    // Try getting episodes from maxstream
                    val eps = if (uprotLink != null) {
                        getNestedEpisodes(uprotLink, seasonNumber)
                    } else {
                        null
                    }

                    if (eps.isNullOrEmpty()) {
                        // If I can't find episodes on maxstream it's very likely
                        // that I found the video source
                        newEpisode(links.toJson()) {
                            name = epName
                            season = seasonNumber
                            episode = epNumber
                        }
                    } else {
                        nestedEps.addAll(eps)
                        null
                    }
                } else {
                    null
                }
            }.filterNotNull()
        }?.flatten()

        if (nestedEps.isNotEmpty()) {
            val eps = nestedEps.toMutableList()
            eps.addAll(episodes ?: emptyList())
            return eps to seasonsData
        }

        return (episodes ?: emptyList()) to seasonsData
    }

    private suspend fun getNestedEpisodes(
        uprotLink: String,
        season: Int?,
    ): List<Episode> {
        val link = ShortLink.unshortenUprot(uprotLink)
        if (link.toHttpUrlOrNull() != null) {
            val response = app.get(link)
            val trs = response.document.select("tr")
            var epNum = 0
            val episodes = trs.map {
                val tds = it.select("td")
                val epLink = tds[1].select("a").attr("href")
                val name = tds.first()?.text()
                epNum++
                newEpisode(listOf(epLink).toJson()) {
                    this.name = name
                    this.season = season
                    this.episode = epNum
                }
            }
            return episodes
        }
        return emptyList()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        Log.d("CB01:loadLinks", "=== INIZIO loadLinks ===")
        Log.d("CB01:loadLinks", "Data ricevuta: $data")
        
        if (data == "null") {
            Log.d("CB01:loadLinks", "Data è null, esco")
            return false
        }
        
        try {
            // Parsa i link dal JSON
            val links = parseJson<List<String>>(data)
            Log.d("CB01:loadLinks", "Links parsati: $links")
            
            // Filtra solo uprot (Maxstream) e stayonline (Mixdrop)
            val filteredLinks = links.filter { 
                it.contains("uprot.net") || it.contains("stayonline") 
            }
            
            Log.d("CB01:loadLinks", "Links filtrati: $filteredLinks")
            
            if (filteredLinks.isEmpty()) {
                Log.d("CB01:loadLinks", "Nessun link valido trovato")
                return false
            }
            
            // Se ci sono più di 2 link, prendi gli ultimi 2 (sono quelli giusti)
            val finalLinks = if (filteredLinks.size > 2) {
                filteredLinks.subList(filteredLinks.size - 2, filteredLinks.size)
            } else {
                filteredLinks
            }
            
            Log.d("CB01:loadLinks", "Links finali da processare: $finalLinks")
            
            // Processa ogni link
            finalLinks.forEachIndexed { index, originalLink ->
                Log.d("CB01:loadLinks", "\n--- Processando link ${index + 1}: $originalLink")
                
                // 1. Bypassa lo shortlink
                val bypassedLink = bypassShortLink(originalLink)
                
                if (bypassedLink != null) {
                    Log.d("CB01:loadLinks", "✅ Bypass riuscito: $bypassedLink")
                    
                    // 2. Processa il link con l'estrattore appropriato
                    processLinkWithExtractor(bypassedLink, originalLink, subtitleCallback, callback)
                } else {
                    Log.d("CB01:loadLinks", "❌ Bypass fallito per: $originalLink")
                }
            }
            
            Log.d("CB01:loadLinks", "=== FINE loadLinks ===")
            return true
            
        } catch (e: Exception) {
            Log.e("CB01:loadLinks", "💥 Errore in loadLinks: ${e.message}", e)
            return false
        }
    }
    
    // NUOVA FUNZIONE: Bypassa tutti gli shortlink
    private suspend fun bypassShortLink(link: String): String? {
        return try {
            when {
                link.contains("stayonline.pro") -> {
                    Log.d("CB01:bypassShortLink", "Bypassando stayonline: $link")
                    bypassStayOnline(link)
                }
                
                link.contains("uprot.net") -> {
                    Log.d("CB01:bypassShortLink", "Bypassando uprot: $link")
                    // Prova prima il metodo standard
                    val result = ShortLink.unshortenUprot(link)
                    if (result == null || result.contains("uprot.net")) {
                        // Fallback al nostro metodo
                        bypassUprot(link)
                    } else {
                        result
                    }
                }
                
                else -> {
                    Log.d("CB01:bypassShortLink", "Link non shortlink: $link")
                    link
                }
            }
        } catch (e: Exception) {
            Log.e("CB01:bypassShortLink", "Errore bypass: ${e.message}")
            null
        }
    }
    
    // NUOVA FUNZIONE: Processa link con estrattore appropriato
    private suspend fun processLinkWithExtractor(
        finalUrl: String,
        originalUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("CB01:processLink", "URL finale: $finalUrl")
        Log.d("CB01:processLink", "URL originale: $originalUrl")
        
        try {
            // Determina quale estrattore usare in base all'URL FINALE
            when {
                // M1XDROP (nuovo MixDrop) - m1xdrop.net
                finalUrl.contains("m1xdrop.net") -> {
                    Log.d("CB01:processLink", "✅ Rilevato M1xDrop (nuovo MixDrop)")
                    
                    // Pulisci l'URL per MixDrop
                    val cleanUrl = finalUrl.substringBefore("?")
                    Log.d("CB01:processLink", "URL pulito per MixDrop: $cleanUrl")
                    
                    // Usa MixDropExtractor (che ora punta a m1xdrop.net)
                    ioSafe {
                        MixDropExtractor().getUrl(cleanUrl, "", subtitleCallback, callback)
                    }
                }
                
                // MAXSUN (nuovo MaxStream) - maxsun435.online
                finalUrl.contains("maxsun435.online") -> {
                    Log.d("CB01:processLink", "✅ Rilevato MaxSun (nuovo MaxStream)")
                    
                    // Usa MaxStreamExtractor (che ora punta a maxsun435.online)
                    ioSafe {
                        MaxStreamExtractor().getUrl(finalUrl, null, subtitleCallback, callback)
                    }
                }
                
                // VECCHI DOMINI PER COMPATIBILITÀ
                finalUrl.contains("mixdrop.") -> {
                    Log.d("CB01:processLink", "⚠️ Rilevato vecchio dominio MixDrop")
                    Log.d("CB01:processLink", "   Provo comunque MixDropExtractor...")
                    
                    ioSafe {
                        MixDropExtractor().getUrl(finalUrl, "", subtitleCallback, callback)
                    }
                }
                
                finalUrl.contains("maxstream.") -> {
                    Log.d("CB01:processLink", "⚠️ Rilevato vecchio dominio MaxStream")
                    Log.d("CB01:processLink", "   Provo comunque MaxStreamExtractor...")
                    
                    ioSafe {
                        MaxStreamExtractor().getUrl(finalUrl, null, subtitleCallback, callback)
                    }
                }
                
                // DOMINIO SCONOSCIUTO - Prova a indovinare
                else -> {
                    Log.d("CB01:processLink", "❓ Dominio non riconosciuto: $finalUrl")
                    Log.d("CB01:processLink", "   Provo a indovinare l'estrattore...")
                    
                    // Prova MixDrop se sembra mixdrop
                    if (finalUrl.contains("mixdrop") || finalUrl.contains("m1xdrop") || 
                        originalUrl.contains("stayonline")) {
                        Log.d("CB01:processLink", "   Sembra MixDrop, provo MixDropExtractor")
                        ioSafe {
                            MixDropExtractor().getUrl(finalUrl(finalUrl, "", subtitleCallback, callback)
                        }
                    }
                    // Prova MaxStream se sembra maxstream
                    else if (finalUrl.contains("maxstream") || finalUrl.contains("maxsun") || 
                             originalUrl.contains("uprot")) {
                        Log.d("CB01:processLink", "   Sembra MaxStream, provo MaxStreamExtractor")
                        ioSafe {
                            MaxStreamExtractor().getUrl(finalUrl, null, subtitleCallback, callback)
                        }
                    } else {
                        Log.d("CB01:processLink", "   ❌ Impossibile determinare l'estrattore")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("CB01:processLink", "💥 Errore processLink: ${e.message}", e)
        }
    }

    private suspend fun bypassStayOnline(link: String): String? {
        Log.d("CB01:bypassStayOnline", "Original: $link")
        
        try {
            val headers = mapOf(
                "origin" to "https://stayonline.pro",
                "referer" to link,
                "host" to "stayonline.pro",
                "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
                "x-requested-with" to "XMLHttpRequest",
                "accept" to "application/json, text/javascript, */*; q=0.01",
                "accept-language" to "it-IT,it;q=0.9,en;q=0.8",
                "content-type" to "application/x-www-form-urlencoded; charset=UTF-8"
            )
            
            // Estrai l'ID dal link
            val id = link.split("/").dropLast(1).lastOrNull() ?: link.substringAfterLast("/")
            val data = "id=$id&ref="
            
            Log.d("CB01:bypassStayOnline", "ID: $id, Data: $data")
            
            val response = app.post(
                "https://stayonline.pro/ajax/linkEmbedView.php",
                headers = headers,
                requestBody = data.toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaTypeOrNull()),
                timeout = 30000
            )
            
            val jsonResponse = response.body.string()
            Log.d("CB01:bypassStayOnline", "Risposta JSON: $jsonResponse")
            
            try {
                val json = JSONObject(jsonResponse)
                val success = json.optBoolean("success", false)
                
                if (success) {
                    val realUrl = json.getJSONObject("data").getString("value")
                    Log.d("CB01:bypassStayOnline", "✅ URL reale: $realUrl")
                    return realUrl
                } else {
                    Log.e("CB01:bypassStayOnline", "❌ API ha restituito success=false")
                    return null
                }
            } catch (e: JSONException) {
                Log.e("CB01:bypassStayOnline", "Errore parsing JSON: ${e.message}")
                
                // Fallback: cerca URL nel testo
                val urlPattern = Regex("""https?://[^\s"']+""")
                val foundUrls = urlPattern.findAll(jsonResponse).map { it.value }.toList()
                
                if (foundUrls.isNotEmpty()) {
                    val fallbackUrl = foundUrls.first()
                    Log.d("CB01:bypassStayOnline", "🔄 Fallback URL: $fallbackUrl")
                    return fallbackUrl
                }
                
                return null
            }
        } catch (e: Exception) {
            Log.e("CB01:bypassStayOnline", "💥 Errore generico: ${e.message}", e)
            return null
        }
    }

    private suspend fun bypassUprot(link: String): String? {
        Log.d("CB01:bypassUprot", "Original: $link")
        
        try {
            val updatedLink = if ("msf" in link) link.replace("msf", "mse") else link

            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Accept-Language" to "it-IT,it;q=0.9,en;q=0.8",
                "Accept-Encoding" to "gzip, deflate, br",
                "DNT" to "1",
                "Connection" to "keep-alive",
                "Upgrade-Insecure-Requests" to "1",
                "Sec-Fetch-Dest" to "document",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "none",
                "Sec-Fetch-User" to "?1"
            )
            
            Log.d("CB01:bypassUprot", "Aggiornato: $updatedLink")
            
            val response = app.get(updatedLink, headers = headers, timeout = 15000)
            
            Log.d("CB01:bypassUprot", "Status: ${response.code}")
            
            // Controlla redirect
            val finalUrl = response.url
            if (finalUrl != updatedLink && !finalUrl.contains("uprot.net")) {
                Log.d("CB01:bypassUprot", "✅ Redirect a: $finalUrl")
                return finalUrl
            }
            
            val document = response.document
            val links = document.select("a")
            
            // Cerca link che contengono maxstream o maxsun
            val maxLink = links.firstOrNull { 
                it.attr("href").contains("maxstream") || 
                it.attr("href").contains("maxsun") ||
                it.attr("href").contains("watchfree")
            }
            
            if (maxLink != null) {
                val href = maxLink.attr("href")
                Log.d("CB01:bypassUprot", "✅ Trovato link: $href")
                return href
            }
            
            // Cerca meta refresh
            val metaRefresh = document.select("meta[http-equiv='refresh']").firstOrNull()
            metaRefresh?.let {
                val content = it.attr("content")
                val urlMatch = Regex("""url=(.+)""", RegexOption.IGNORE_CASE).find(content)
                urlMatch?.let { match ->
                    val refreshUrl = match.groupValues[1]
                    Log.d("CB01:bypassUprot", "✅ Meta refresh a: $refreshUrl")
                    return refreshUrl
                }
            }
            
            Log.e("CB01:bypassUprot", "❌ Nessun link trovato nella pagina")
            return null
            
        } catch (e: Exception) {
            Log.e("CB01:bypassUprot", "💥 Errore: ${e.message}", e)
            return null
        }
    }

    data class Post(
        @JsonProperty("id") val id: String,
        @JsonProperty("popup") val popup: String,
        @JsonProperty("unique_id") val uniqueId: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("permalink") val permalink: String,
        @JsonProperty("item_id") val itemId: String,
        var poster: String? = null,
    )
}

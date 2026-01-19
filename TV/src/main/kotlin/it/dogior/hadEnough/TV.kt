package it.dogior.hadEnough

import android.content.SharedPreferences
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

class TV(
    private val enabledPlaylists: List<String>,
    override var lang: String,
    private val sharedPref: SharedPreferences?
) : MainAPI() {
    override var mainUrl =
        "https://raw.githubusercontent.com/Free-TV/IPTV/refs/heads/master/playlists"
    override var name = "TV"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false
    override var sequentialMainPage = true
    override val supportedTypes = setOf(TvType.Live)
    private val playlists = mutableMapOf<String, Playlist?>()
    private val urlList = enabledPlaylists.map { "$mainUrl/$it" }

    private suspend fun getTVChannels(url: String): List<TVChannel> {
        if (playlists[url] == null) {
            playlists[url] = IptvPlaylistParser().parseM3U(app.get(url).text)
        }
        return playlists[url]?.items ?: emptyList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (urlList.isEmpty()) {
            return newHomePageResponse(
                listOf(
                    HomePageList(
                        "Enable channels in the plugin settings",
                        emptyList(),
                        isHorizontalImages = true
                    )
                ), false
            )
        }
        val sections = urlList.map { url ->
            val data = getTVChannels(url)
            val sectionTitle = url.substringAfter("playlist_", "").substringBefore(".m3u8").trim()
                .capitalize()
            val shows = data.map { channel ->
                sharedPref?.edit()?.apply {
                    putString(channel.url ?: "", channel.toJson())
                    apply()
                }
                channel.toSearchResponse(this@TV)
            }
            HomePageList(
                sectionTitle,
                shows,
                isHorizontalImages = true
            )
        }
        return newHomePageResponse(sections, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val allChannels = urlList.flatMap { getTVChannels(it) }
        val filtered = allChannels.filter { channel ->
            channel.attributes["tvg-id"]?.contains(query, ignoreCase = true) == true ||
                    channel.title?.contains(query, ignoreCase = true) == true
        }
        return filtered.map { it.toSearchResponse(this) }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse {
        val tvChannelJson = sharedPref?.getString(url, null)
            ?: throw ErrorLoadingException("Error loading channel from cache")

        val tvChannel = parseJson<TVChannel>(tvChannelJson)
        val streamUrl = tvChannel.url ?: throw ErrorLoadingException("Stream URL missing")
        val channelName = tvChannel.title ?: tvChannel.attributes["tvg-id"] ?: "Unknown"
        val posterUrl = tvChannel.attributes["tvg-logo"]

        return newLiveStreamLoadResponse(
            channelName,
            streamUrl,
            streamUrl
        ) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        callback(
            newExtractorLink(
                this.name,
                this.name,
                data,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = ""
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }
}

data class Playlist(
    val items: List<TVChannel> = emptyList(),
)

data class TVChannel(
    val title: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val url: String? = null,
    val userAgent: String? = null,
) {
    fun toSearchResponse(api: MainAPI): SearchResponse {
        val streamUrl = url ?: ""
        val channelName = title ?: attributes["tvg-id"] ?: "Unknown"
        val posterUrl = attributes["tvg-logo"]
        return api.newLiveSearchResponse(
            channelName,
            streamUrl,
            TvType.Live
        ) {
            this.posterUrl = posterUrl
        }
    }
}

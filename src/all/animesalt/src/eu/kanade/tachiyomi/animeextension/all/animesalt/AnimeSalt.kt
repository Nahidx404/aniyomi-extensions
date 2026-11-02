package eu.kanade.tachiyomi.animeextension.all.animesalt

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.unpacker.Unpacker
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnimeSalt : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Anime Salt"

    override val lang = "all"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // The site changes TLD from time to time, so the domain is a preference.
    override val baseUrl: String
        get() = preferences.getString(PREF_BASE_URL_KEY, DEFAULT_BASE_URL)!!
            .trim()
            .ifEmpty { DEFAULT_BASE_URL }
            .trimEnd('/')

    override val client = network.cloudflareClient

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request =
        GET(listUrl("category/type/anime", page), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseListing(response)

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request =
        GET(listUrl("category/status/ongoing", page), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseListing(response)

    // ============================== Search ================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            val path = if (page > 1) "page/$page" else ""
            val url = listUrl(path, 1).toHttpUrl().newBuilder()
                .addQueryParameter("s", query.trim())
                .build()
            return GET(url, headers)
        }

        val genre = filters.selected("Genre")
        val language = filters.selected("Language")
        val network = filters.selected("Network")
        val status = filters.selected("Status")
        val type = filters.selected("Type").ifEmpty { "anime" }
        val format = filters.selected("Format")

        val path = when {
            genre.isNotEmpty() -> "category/genre/$genre"
            language.isNotEmpty() -> "category/language/$language"
            network.isNotEmpty() -> "category/network/$network"
            status.isNotEmpty() -> "category/status/$status"
            else -> "category/type/$type"
        }

        val url = listUrl(path, page).toHttpUrl().newBuilder().apply {
            if (format.isNotEmpty()) addQueryParameter("type", format)
        }.build()
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseListing(response)

    // ============================== Filters ===============================

    private class SelectFilter(
        name: String,
        private val options: List<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(name, options.map { it.first }.toTypedArray()) {
        val selected: String get() = options[state].second
    }

    private fun AnimeFilterList.selected(name: String): String =
        filterIsInstance<SelectFilter>().firstOrNull { it.name == name }?.selected.orEmpty()

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("Text search ignores the filters below"),
        AnimeFilter.Header("Only one of Genre / Language / Network / Status applies (in that order), otherwise Type"),
        SelectFilter("Type", TYPES),
        SelectFilter("Format", FORMATS),
        SelectFilter("Genre", GENRES),
        SelectFilter("Language", LANGUAGES),
        SelectFilter("Network", NETWORKS),
        SelectFilter("Status", STATUSES),
    )

    // ============================== Listings ==============================

    private fun listUrl(path: String, page: Int): String {
        val cleanPath = path.trim('/')
        val sb = StringBuilder(baseUrl).append('/')
        if (cleanPath.isNotEmpty()) sb.append(cleanPath).append('/')
        if (page > 1 && !cleanPath.startsWith("page/")) sb.append("page/").append(page).append('/')
        return sb.toString()
    }

    private fun parseListing(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animes = doc.select("li:has(h2):has(a[href*=/series/], a[href*=/movies/])")
            .mapNotNull { it.toAnime() }
            .distinctBy { it.url }

        val hasNext = animes.isNotEmpty() && doc.select("a").any {
            it.ownText().trim().equals("next", ignoreCase = true) || it.hasClass("next")
        }
        return AnimesPage(animes, hasNext)
    }

    private fun Element.toAnime(): SAnime? {
        val href = select("a[href*=/series/], a[href*=/movies/]")
            .map { it.absUrl("href") }
            .filter { it.isNotBlank() }
            .distinct()
            .singleOrNull() ?: return null

        val title = selectFirst("h2")?.text()?.trim().orEmpty()
            .ifEmpty { selectFirst("img")?.attr("alt")?.removePrefix("Image")?.trim().orEmpty() }
        if (title.isEmpty()) return null

        return SAnime.create().apply {
            setUrlWithoutDomain(href)
            this.title = title
            thumbnail_url = selectFirst("img")?.imageUrl()
        }
    }

    private fun Element.imageUrl(): String? {
        for (attr in IMAGE_ATTRS) {
            val value = absUrl(attr)
            if (value.isNotBlank() && !value.startsWith("data:")) return value
        }
        return null
    }

    // ============================== Details ===============================

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        val pageTitle = doc.selectFirst("h1")?.text()?.trim().orEmpty()

        return SAnime.create().apply {
            title = pageTitle
            thumbnail_url = doc.select("img")
                .firstOrNull { it.attr("alt").trim() == "Image $pageTitle" }
                ?.imageUrl()
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

            genre = doc.select("a[href*=/category/genre/]")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString()

            status = if (doc.selectFirst("a[href*=/category/status/ongoing]") != null) {
                SAnime.ONGOING
            } else {
                SAnime.UNKNOWN
            }

            val languages = doc.select("a[href*=/category/language/]").map { it.text().trim() }.distinct()
            val networks = doc.select("a[href*=/category/network/]").map { it.text().trim() }.distinct()
            description = buildString {
                append(doc.overviewText())
                if (languages.isNotEmpty()) append("\n\nLanguages: ").append(languages.joinToString())
                if (networks.isNotEmpty()) append("\nNetwork: ").append(networks.joinToString())
            }.trim()
        }
    }

    private fun Document.overviewText(): String {
        val header = select("h1, h2, h3, h4, h5, h6, div, span, p")
            .firstOrNull { it.ownText().trim().equals("Overview", ignoreCase = true) }

        val fromPage = header?.parent()?.select("p")
            ?.map { it.text().trim() }
            ?.filter { it.isNotEmpty() && !it.equals("Read More", ignoreCase = true) }
            ?.joinToString("\n\n")
            .orEmpty()

        return fromPage.ifEmpty { selectFirst("meta[name=description]")?.attr("content").orEmpty() }
    }

    // ============================== Episodes ==============================

    private class Ep(val season: Int, val number: Int, var title: String?, var sub: Boolean) {
        var path: String? = null
    }

    /** Registers every episode link on the page; returns the series part of the episode slug. */
    private fun Document.collectEpisodes(episodes: MutableMap<Pair<Int, Int>, Ep>): String? {
        var base: String? = null
        select("a[href*=/episode/]").forEach { a ->
            val epPath = a.absUrl("href").toHttpUrlOrNull()?.encodedPath ?: return@forEach
            val match = EPISODE_PATH_REGEX.find(epPath) ?: return@forEach
            if (base == null) base = match.groupValues[1]
            val season = match.groupValues[2].toInt()
            val number = match.groupValues[3].toInt()
            val title = a.episodeTitle()
            val ep = episodes.getOrPut(season to number) { Ep(season, number, title, false) }
            if (ep.title == null) ep.title = title
            if (ep.path == null) ep.path = epPath
        }
        return base
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val path = response.request.url.encodedPath

        if (path.startsWith("/movies/")) {
            return listOf(
                SEpisode.create().apply {
                    url = path
                    name = "Movie"
                    episode_number = 1F
                },
            )
        }

        val slug = path.trim('/').substringAfterLast('/')
        val episodes = linkedMapOf<Pair<Int, Int>, Ep>()
        val seasonStarts = linkedMapOf<Int, Int>()

        // 1) Episodes present in the HTML (normally only the first season)
        val base = doc.collectEpisodes(episodes)

        // 2) Remaining seasons come from the season tabs: "Season 2 • 33-53 (21) [Sub]"
        doc.body().text().let { text ->
            SEASON_TAB_REGEX.findAll(text).forEach { m ->
                val season = m.groupValues[1].toInt()
                val from = m.groupValues[2].toInt()
                val to = m.groupValues[3].ifEmpty { m.groupValues[2] }.toInt()
                val sub = m.groupValues[4].contains("sub", ignoreCase = true)
                if (to < from || to - from > MAX_SEASON_SPAN) return@forEach
                seasonStarts[season] = from
                for (number in from..to) {
                    val ep = episodes.getOrPut(season to number) { Ep(season, number, null, sub) }
                    if (sub) ep.sub = true
                }
            }
        }

        val epBase = base ?: slug

        // 3) The page of a season's first episode lists that season's real titles and links
        val seasonNotes = mutableMapOf<Int, String>()
        seasonStarts
            .filterKeys { s -> episodes.values.none { it.season == s && it.title != null } }
            .forEach { (s, from) ->
                seasonNotes[s] = runCatching {
                    client.newCall(GET("$baseUrl/episode/$epBase-${s}x$from/", headers)).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val page = resp.asJsoup()
                            page.collectEpisodes(episodes)
                            "no titles on site (${page.select("li a[href*=/episode/]").size} items)"
                        } else {
                            "season page HTTP ${resp.code}"
                        }
                    }
                }.getOrElse { "season page failed: ${it.javaClass.simpleName}" }
            }

        val multiSeason = episodes.keys.map { it.first }.distinct().size > 1

        return episodes.values
            .sortedWith(compareByDescending<Ep> { it.season }.thenByDescending { it.number })
            .map { ep ->
                SEpisode.create().apply {
                    url = ep.path ?: "/episode/$epBase-${ep.season}x${ep.number}/"
                    val prefix = if (multiSeason) "S${ep.season} E${ep.number}" else "Episode ${ep.number}"
                    name = if (ep.title.isNullOrBlank()) prefix else "$prefix - ${ep.title}"
                    episode_number = ep.number.toFloat()
                    scanlator = when {
                        ep.sub -> "Sub"
                        ep.title.isNullOrBlank() -> seasonNotes[ep.season]
                        else -> null
                    }
                }
            }
    }

    private fun Element.episodeTitle(): String? {
        var node: Element? = this
        while (node != null && node.tagName() != "li") node = node.parent()
        val item = node ?: return null

        return sequenceOf(
            item.selectFirst("h2, h3")?.let { it.ownText().ifBlank { it.text() } },
            item.selectFirst("a[title]")?.attr("title"),
            item.selectFirst("img[alt]")?.attr("alt")?.removePrefix("Image"),
        )
            .mapNotNull { it?.trim()?.removeSuffix("View")?.trim() }
            .firstOrNull { it.isNotEmpty() && !PLACEHOLDER_TITLE_REGEX.matches(it) }
    }

    // ============================== Videos ================================

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val pageUrl = response.request.url.toString()
        val embeds = collectEmbeds(doc, response.request.url.encodedPath)

        val errors = mutableListOf<String>()
        val videos = embeds
            .flatMap { embed ->
                runCatching { videosFromEmbed(embed, pageUrl) }
                    .onFailure { errors += "${embed.take(70)} -> ${it.message?.take(60)}" }
                    .getOrDefault(emptyList())
            }
            .sortedByPreference()

        if (videos.isEmpty()) {
            throw Exception(
                buildString {
                    append("No playable video found on ").append(response.request.url.encodedPath)
                    append(". Embeds seen: ").append(embeds.size)
                    embeds.take(4).forEach { append("\n• ").append(it.take(80)) }
                    errors.take(3).forEach { append("\n! ").append(it) }
                },
            )
        }
        return videos
    }

    private fun collectEmbeds(doc: Document, pagePath: String): List<String> {
        val urls = linkedSetOf<String>()

        doc.select("iframe").forEach {
            urls += it.absUrl("src")
            urls += it.absUrl("data-src")
        }

        doc.select(EMBED_ATTR_SELECTOR).filterNot { it.tagName() == "img" }.forEach { el ->
            EMBED_ATTRS.forEach { attr -> urls += el.absUrl(attr) }
        }

        // Player links that only appear as plain text / inside scripts
        val html = doc.html().replace("\\/", "/")
        PLAYER_LINK_REGEX.findAll(html).forEach { urls += it.value }

        // Toroplay theme: servers are the site's own "?trembed=N&trid=ID&trtype=T" player pages
        if (urls.none { it.contains("trembed") }) {
            TRID_REGEX.find(html)?.groupValues?.get(1)?.let { trid ->
                val type = if (pagePath.startsWith("/movies/")) 1 else 2
                for (i in 0..3) urls += "$baseUrl/?trembed=$i&trid=$trid&trtype=$type"
            }
        }

        return urls
            .filter { it.startsWith("http") }
            .filterNot { url -> BLOCKED_PARTS.any { url.contains(it, ignoreCase = true) } }
            .filterNot { url -> NOT_A_PLAYER_EXT.any { url.substringBefore('?').endsWith(it, ignoreCase = true) } }
            .distinct()
    }

    private fun videosFromEmbed(embedUrl: String, referer: String, depth: Int = 0): List<Video> {
        if (depth > MAX_EMBED_DEPTH) return emptyList()
        val httpUrl = embedUrl.toHttpUrlOrNull() ?: return emptyList()
        val label = serverLabel(httpUrl.host)
        val pageHeaders = headers.newBuilder().set("Referer", referer).build()
        val path = httpUrl.encodedPath

        // A direct stream file
        if (path.endsWith(".m3u8") || path.endsWith(".mp4")) {
            return videosFromStreams(listOf(embedUrl), embedUrl, label, pageHeaders)
        }

        // FirePlayer-style hosts: https://host/video/<hash>
        FIREPLAYER_PATH_REGEX.find(path)?.let { m ->
            val videos = runCatching { fireplayerVideos(httpUrl, m.groupValues[1], label, referer) }
                .getOrDefault(emptyList())
            if (videos.isNotEmpty()) return videos
        }

        val body = client.newCall(GET(embedUrl, pageHeaders)).execute()
            .use { if (it.isSuccessful) it.body.string() else "" }
        if (body.isBlank()) return emptyList()

        val script = unpackScripts(body).replace("\\/", "/")
        val streams = linkedSetOf<String>()
        STREAM_REGEXES.forEach { regex ->
            regex.findAll(script).forEach { m ->
                val raw = m.groupValues[1]
                val resolved = if (raw.startsWith("http")) raw else httpUrl.resolve(raw)?.toString()
                if (resolved != null) streams += resolved
            }
        }
        if (streams.isNotEmpty()) {
            val found = videosFromStreams(streams, embedUrl, label, pageHeaders, findSubtitles(script))
            if (found.isNotEmpty()) return found
        }

        // Nested embeds: the site's own "?trembed=" pages only contain an iframe to the real host
        val nested = linkedSetOf<String>()
        Jsoup.parse(body, embedUrl).select("iframe").forEach {
            nested += it.absUrl("src")
            nested += it.absUrl("data-src")
        }
        PLAYER_LINK_REGEX.findAll(script).forEach { nested += it.value }
        listOf(META_REFRESH_REGEX, JS_REDIRECT_REGEX).forEach { regex ->
            regex.find(body)?.groupValues?.get(1)?.let { raw ->
                httpUrl.resolve(raw)?.toString()?.let { nested += it }
            }
        }

        return nested
            .filter { it.startsWith("http") && it != embedUrl }
            .filterNot { url -> BLOCKED_PARTS.any { url.contains(it, ignoreCase = true) } }
            .filterNot { url -> NOT_A_PLAYER_EXT.any { url.substringBefore('?').endsWith(it, ignoreCase = true) } }
            .flatMap { runCatching { videosFromEmbed(it, embedUrl, depth + 1) }.getOrDefault(emptyList()) }
    }

    private fun videosFromStreams(
        streams: Collection<String>,
        embedUrl: String,
        label: String,
        base: Headers,
        subs: List<Track> = emptyList(),
    ): List<Video> {
        val videos = mutableListOf<Video>()
        streams.forEach { stream ->
            if (stream.substringBefore('?').endsWith(".mp4")) {
                val mp4Headers = base.newBuilder().set("Referer", embedUrl).build()
                videos += Video(stream, "$label - MP4", stream, mp4Headers, subtitleTracks = orderedSubtitles(subs))
            } else {
                videos += runCatching { hlsVideos(stream, embedUrl, label, subs) }.getOrDefault(emptyList())
            }
        }
        return videos
    }

    /** FirePlayer: POST /player/index.php?data=<hash>&do=getVideo returns the HLS master playlist. */
    private fun fireplayerVideos(url: HttpUrl, hash: String, label: String, referer: String): List<Video> {
        val host = "${url.scheme}://${url.host}"
        val apiHeaders = headers.newBuilder()
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Referer", url.toString())
            .set("Origin", host)
            .build()
        val form = FormBody.Builder()
            .add("hash", hash)
            .add("r", "")
            .build()

        val text = client.newCall(POST("$host/player/index.php?data=$hash&do=getVideo", apiHeaders, body = form))
            .execute()
            .use { it.body.string() }
            .replace("\\/", "/")

        val master = SECURED_LINK_REGEX.find(text)?.groupValues?.get(1)
            ?: VIDEO_SOURCE_REGEX.find(text)?.groupValues?.get(1)
            ?: return emptyList()

        // The player page / API reply can also list external subtitle files
        val pageText = runCatching {
            val pageHeaders = headers.newBuilder().set("Referer", referer).build()
            client.newCall(GET(url.toString(), pageHeaders)).execute()
                .use { if (it.isSuccessful) it.body.string() else "" }
        }.getOrDefault("")

        return hlsVideos(master, url.toString(), label, findSubtitles(pageText + "\n" + text))
    }

    // ============================ HLS / tracks ============================

    private fun hlsVideos(playlistUrl: String, referer: String, label: String, externalSubs: List<Track>): List<Video> {
        val videoHeaders = playlistUtils.generateMasterHeaders(headers, referer)
        val master = client.newCall(GET(playlistUrl, videoHeaders)).execute().use { it.body.string() }
        val base = playlistUrl.toHttpUrl()
        fun absolute(raw: String): String? = base.resolve(raw.trim())?.toString()

        val media = MEDIA_TAG_REGEX.findAll(master).map { parseHlsAttributes(it.groupValues[1]) }.toList()
        val audio = media
            .filter { it["TYPE"] == "AUDIO" }
            .mapNotNull { m ->
                val uri = m["URI"]?.let(::absolute) ?: return@mapNotNull null
                Track(uri, m["NAME"] ?: m["LANGUAGE"] ?: "Audio")
            }
        val embeddedSubs = media
            .filter { it["TYPE"] == "SUBTITLES" }
            .mapNotNull { m ->
                val uri = m["URI"]?.let(::absolute) ?: return@mapNotNull null
                Track(uri, m["NAME"] ?: m["LANGUAGE"] ?: "Subtitle")
            }

        val audioTracks = orderedAudio(audio)
        val subtitleTracks = orderedSubtitles(externalSubs + embeddedSubs)

        if (!master.contains("#EXT-X-STREAM-INF")) {
            return listOf(
                Video(
                    playlistUrl,
                    "$label - Video",
                    playlistUrl,
                    headers = videoHeaders,
                    subtitleTracks = subtitleTracks,
                    audioTracks = audioTracks,
                ),
            )
        }

        // Several separate audio files: one entry per language, so the language can be picked
        // (or downloaded) from the video list.
        val perLanguage = audioTracks.size >= 2
        val mixedAudio = media
            .filter { it["TYPE"] == "AUDIO" && it["URI"] == null }
            .mapNotNull { it["NAME"] ?: it["LANGUAGE"] }

        return master.substringAfter("#EXT-X-STREAM-INF:").split("#EXT-X-STREAM-INF:").flatMap { chunk ->
            val info = chunk.substringBefore("\n")
            val quality = RESOLUTION_REGEX.find(info)?.groupValues?.get(1)?.let { "${it}p" } ?: "Video"
            val streamUrl = chunk.substringAfter("\n").lineSequence()
                .map(String::trim)
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                ?.let(::absolute)
                ?: return@flatMap emptyList<Video>()

            if (perLanguage) {
                audioTracks.map { track ->
                    Video(
                        streamUrl,
                        "$label - $quality [${track.lang}]",
                        streamUrl,
                        headers = videoHeaders,
                        subtitleTracks = subtitleTracks,
                        audioTracks = listOf(track),
                    )
                }
            } else {
                val note = if (mixedAudio.size >= 2) " [audio inside stream: ${mixedAudio.joinToString()}]" else ""
                listOf(
                    Video(
                        streamUrl,
                        "$label - $quality$note",
                        streamUrl,
                        headers = videoHeaders,
                        subtitleTracks = subtitleTracks,
                        audioTracks = audioTracks,
                    ),
                )
            }
        }
    }

    private fun parseHlsAttributes(line: String): Map<String, String> =
        HLS_ATTR_REGEX.findAll(line).associate { it.groupValues[1] to it.groupValues[2].trim('"') }

    private fun findSubtitles(text: String): List<Track> {
        val clean = text.replace("\\/", "/")
        val found = linkedMapOf<String, String>() // url -> label

        fun isSubtitle(url: String) = SUBTITLE_SKIP_PARTS.none { url.contains(it, ignoreCase = true) }

        JSON_OBJECT_REGEX.findAll(clean).forEach { m ->
            val obj = m.value
            if (obj.contains("thumbnails", ignoreCase = true)) return@forEach
            val url = SUBTITLE_URL_REGEX.find(obj)?.value ?: return@forEach
            if (url !in found && isSubtitle(url)) {
                found[url] = SUBTITLE_LABEL_REGEX.find(obj)?.groupValues?.get(1) ?: labelFromUrl(url)
            }
        }
        BRACKET_SUBTITLE_REGEX.findAll(clean).forEach { m ->
            val url = m.groupValues[2]
            if (url !in found && isSubtitle(url)) found[url] = m.groupValues[1]
        }
        SUBTITLE_URL_REGEX.findAll(clean).forEach { m ->
            if (m.value !in found && isSubtitle(m.value)) found[m.value] = labelFromUrl(m.value)
        }

        return found.map { (url, label) -> Track(url, label.trim().ifEmpty { "Subtitle" }) }
    }

    private fun labelFromUrl(url: String): String =
        url.substringBefore('?').substringAfterLast('/').substringBeforeLast('.')

    private fun orderedAudio(tracks: List<Track>): List<Track> =
        tracks.orderedBy(preferences.getString(PREF_AUDIO_KEY, PREF_ANY)!!)

    private fun orderedSubtitles(tracks: List<Track>): List<Track> {
        val pref = preferences.getString(PREF_SUB_KEY, PREF_ANY)!!
        return if (pref == PREF_OFF) emptyList() else tracks.orderedBy(pref)
    }

    /** Puts tracks of the preferred language first (the sort is stable). */
    private fun List<Track>.orderedBy(pref: String): List<Track> =
        if (pref == PREF_ANY) this else sortedByDescending { it.lang.matchesLanguage(pref) }

    private fun String.matchesLanguage(code: String): Boolean {
        val aliases = LANGUAGE_ALIASES[code] ?: return contains(code, ignoreCase = true)
        return lowercase().split(NON_LETTER_REGEX).any { it in aliases }
    }

    /** Returns the page source with every `eval(function(p,a,c,k,e,d)...)` block appended in unpacked form. */
    private fun unpackScripts(body: String): String {
        val unpacked = Jsoup.parse(body).select("script")
            .map { it.data() }
            .filter { it.contains("eval(function(p,a,c") }
            .mapNotNull { runCatching { Unpacker.unpack(it) }.getOrNull()?.takeIf(String::isNotBlank) }
        return if (unpacked.isEmpty()) body else body + "\n" + unpacked.joinToString("\n")
    }

    private fun serverLabel(host: String): String {
        if (host.startsWith("as-cdn")) return "AnimeSalt"
        val name = host.removePrefix("www.").substringBeforeLast('.')
        return name.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    private fun List<Video>.sortedByPreference(): List<Video> {
        val preferred = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val audioPref = preferences.getString(PREF_AUDIO_KEY, PREF_ANY)!!
        return sortedWith(
            compareByDescending<Video> {
                audioPref != PREF_ANY && it.quality.substringAfter('[', "").matchesLanguage(audioPref)
            }
                .thenByDescending { it.quality.contains(preferred) }
                .thenByDescending { QUALITY_REGEX.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
        )
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_BASE_URL_KEY
            title = "Base URL"
            summary = "Change this if the site moves to a new domain (default: $DEFAULT_BASE_URL). Restart the app after changing."
            dialogTitle = "Base URL"
            setDefaultValue(DEFAULT_BASE_URL)
            setOnPreferenceChangeListener { _, _ -> true }
        }.also(screen::addPreference)

        val languageChoices = LANGUAGES.drop(1)

        ListPreference(screen.context).apply {
            key = PREF_AUDIO_KEY
            title = "Default audio language"
            entries = (listOf("Default (let the player decide)") + languageChoices.map { it.first })
                .toTypedArray<CharSequence>()
            entryValues = (listOf(PREF_ANY) + languageChoices.map { it.second })
                .toTypedArray<CharSequence>()
            setDefaultValue(PREF_ANY)
            summary = "%s"
            setOnPreferenceChangeListener { _, _ -> true }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SUB_KEY
            title = "Default subtitles"
            entries = (listOf("Default (all, site order)", "Off (hide subtitles)") + languageChoices.map { it.first })
                .toTypedArray<CharSequence>()
            entryValues = (listOf(PREF_ANY, PREF_OFF) + languageChoices.map { it.second })
                .toTypedArray<CharSequence>()
            setDefaultValue(PREF_ANY)
            summary = "%s"
            setOnPreferenceChangeListener { _, _ -> true }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, _ -> true }
        }.also(screen::addPreference)
    }

    companion object {
        private const val DEFAULT_BASE_URL = "https://animesalt.cx"
        private const val PREF_BASE_URL_KEY = "preferred_base_url"
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val MAX_SEASON_SPAN = 5000
        private const val MAX_EMBED_DEPTH = 3

        private const val PREF_AUDIO_KEY = "preferred_audio"
        private const val PREF_SUB_KEY = "preferred_subtitles"
        private const val PREF_ANY = "any"
        private const val PREF_OFF = "off"

        private val PLACEHOLDER_TITLE_REGEX = Regex("""(?i)^(?:episode\s*\d+|.*\b\d+x\d+)$""")

        private val MEDIA_TAG_REGEX = Regex("""#EXT-X-MEDIA:([^\r\n]*)""")
        private val HLS_ATTR_REGEX = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")
        private val RESOLUTION_REGEX = Regex("""RESOLUTION=\d+x(\d+)""")

        private val JSON_OBJECT_REGEX = Regex("""\{[^{}]*\}""")
        private val SUBTITLE_URL_REGEX = Regex("""https?://[^\s"',\]\\]+?\.(?:vtt|srt|ass)(?:\?[^\s"',\]\\]*)?""", RegexOption.IGNORE_CASE)
        private val BRACKET_SUBTITLE_REGEX = Regex("""\[([^\]\[]{1,40})\](https?://[^\s"',\\]+?\.(?:vtt|srt|ass)[^\s"',\\]*)""", RegexOption.IGNORE_CASE)
        private val SUBTITLE_LABEL_REGEX = Regex(""""(?:label|lang|language|name|title)"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
        private val SUBTITLE_SKIP_PARTS = listOf("thumb", "sprite", "storyboard", "preview")

        private val NON_LETTER_REGEX = Regex("[^a-z]+")
        private val LANGUAGE_ALIASES = mapOf(
            "hindi" to setOf("hindi", "hin", "hi"),
            "tamil" to setOf("tamil", "tam", "ta"),
            "telugu" to setOf("telugu", "tel", "te"),
            "english" to setOf("english", "eng", "en"),
            "japanese" to setOf("japanese", "jpn", "jap", "ja"),
            "bengali" to setOf("bengali", "bangla", "ben", "bn"),
            "malayalam" to setOf("malayalam", "mal", "ml"),
            "kannada" to setOf("kannada", "kan", "kn"),
            "korean" to setOf("korean", "kor", "ko"),
        )

        private val TRID_REGEX = Regex("""trid=(\d+)""")
        private val FIREPLAYER_PATH_REGEX = Regex("""/video/([A-Za-z0-9_-]{16,})/?$""")
        private val SECURED_LINK_REGEX = Regex(""""securedLink"\s*:\s*"([^"]+)"""")
        private val VIDEO_SOURCE_REGEX = Regex(""""videoSource"\s*:\s*"([^"]+)"""")
        private val META_REFRESH_REGEX = Regex("""http-equiv=["']refresh["'][^>]*content=["'][^"']*url=([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val JS_REDIRECT_REGEX = Regex("""(?:location(?:\.href)?\s*=|location\.replace\()\s*["']([^"']+)["']""")

        private val IMAGE_ATTRS = listOf("data-src", "data-lazy-src", "data-original", "src")

        private val EPISODE_PATH_REGEX = Regex("""^/episode/(.+)-(\d+)x(\d+)/?$""")
        private val SEASON_TAB_REGEX = Regex(
            """Season\s+(\d+)\s*[•·|]\s*(\d+)(?:\s*[-–]\s*(\d+))?\s*\(\d+\)\s*(\[[A-Za-z ]+])?""",
            RegexOption.IGNORE_CASE,
        )

        private val EMBED_ATTRS = listOf("data-src", "data-url", "data-link", "data-embed", "data-video", "data-source", "data-iframe")
        private val EMBED_ATTR_SELECTOR = EMBED_ATTRS.joinToString(", ") { "[$it]" }
        private val PLAYER_LINK_REGEX = Regex("""https?://[^\s"'<>\\]+/video/[A-Za-z0-9_-]{16,}""")

        private val BLOCKED_PARTS = listOf(
            "youtube.com", "youtu.be", "disqus", "google", "facebook", "twitter", "histats",
            "gravatar", "ui-avatars", "/wp-content/", "/wp-json/", "abyss.to", "short.icu",
        )
        private val NOT_A_PLAYER_EXT = listOf(".js", ".css", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp", ".ico", ".woff", ".woff2")

        private val STREAM_REGEXES = listOf(
            Regex("""["'](https?:[^"'\s]+?\.m3u8[^"'\s]*)["']"""),
            Regex("""hls\d*["']?\s*[:=]\s*["']([^"']+)["']"""),
            Regex("""file\s*:\s*["']([^"']+?\.(?:m3u8|mp4)[^"']*)["']"""),
            Regex("""["'](https?:[^"'\s]+?\.mp4[^"'\s]*)["']"""),
        )

        private val QUALITY_REGEX = Regex("""(\d+)p""")

        private val TYPES = listOf("Anime" to "anime", "Cartoon" to "cartoon")
        private val FORMATS = listOf("All" to "", "Series" to "series", "Movies" to "movies")
        private val STATUSES = listOf("Any" to "", "Ongoing" to "ongoing")

        private val GENRES = listOf(
            "Any" to "",
            "Action" to "action",
            "Adventure" to "adventure",
            "Award Winning" to "award-winning",
            "Comedy" to "comedy",
            "Drama" to "drama",
            "Fantasy" to "fantasy",
            "Horror" to "horror",
            "Mystery" to "mystery",
            "Romance" to "romance",
            "Sci-Fi" to "sci-fi",
            "Seinen" to "seinen",
            "Shoujo" to "shoujo",
            "Shounen" to "shounen",
            "Slice of Life" to "slice-of-life",
            "Sports" to "sports",
            "Supernatural" to "supernatural",
        )

        private val LANGUAGES = listOf(
            "Any" to "",
            "Bengali" to "bengali",
            "English" to "english",
            "Hindi" to "hindi",
            "Japanese" to "japanese",
            "Kannada" to "kannada",
            "Korean" to "korean",
            "Malayalam" to "malayalam",
            "Tamil" to "tamil",
            "Telugu" to "telugu",
        )

        private val NETWORKS = listOf(
            "Any" to "",
            "Cartoon Network" to "cartoon-network",
            "Crunchyroll" to "crunchyroll",
            "Disney" to "disney",
            "Disney Channel" to "disney-channel",
            "Hungama TV" to "hungama-tv",
            "Netflix" to "netflix",
            "Prime Video" to "prime-video",
            "Sony YAY" to "sony-yay",
        )
    }
}

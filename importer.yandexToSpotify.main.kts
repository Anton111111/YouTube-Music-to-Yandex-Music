#!/usr/bin/env kotlin

@file:Repository("https://repo1.maven.org/maven2")
@file:DependsOn("io.ktor:ktor-client-core-jvm:1.6.8")
@file:DependsOn("io.ktor:ktor-client-cio-jvm:1.6.8")
@file:DependsOn("io.ktor:ktor-client-auth-jvm:1.6.8")
@file:DependsOn("io.ktor:ktor-client-logging-jvm:1.6.8")
@file:DependsOn("io.ktor:ktor-client-json-jvm:1.6.8")
@file:DependsOn("io.ktor:ktor-client-gson:1.6.8")
@file:DependsOn("com.ibm.icu:icu4j:68.1")
@file:DependsOn("me.xdrop:fuzzywuzzy:1.3.0")

import com.google.gson.Gson
import com.ibm.icu.text.Transliterator
import io.ktor.client.*
import io.ktor.client.features.*
import io.ktor.client.features.json.*
import io.ktor.client.features.logging.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.URLEncoder


val yConf: YConf = Gson().fromJson(File("config.json").readText(), YConf::class.java)
val sConf: SConf = Gson().fromJson(File("spotify_token.json").readText(), SConf::class.java)
val minFuzzyRatio = 96
val delayForLikes = 100L
val mutex = Mutex()
val mutexLike = Mutex()
val toLatinTrans: Transliterator = Transliterator.getInstance("Any-Latin; NFD;")
val inCyrillicRegex = ".*\\p{InCyrillic}.*".toRegex()
val inNonCyrillicRegex = ".*[^\\p{InCyrillic}].*".toRegex()

val ymClient = HttpClient {
//    install(Logging) {
//        logger = object : Logger {
//            override fun log(message: String) {
//                println(message)
//            }
//        }
//        level = LogLevel.ALL
//    }
    install(JsonFeature) {
        serializer = GsonSerializer()
    }
    defaultRequest {
        header("Authorization", "OAuth ${yConf.token}")
    }
}

val sClient = HttpClient {
//    install(Logging) {
//        logger = object : Logger {
//            override fun log(message: String) {
//                println(message)
//            }
//        }
//        level = LogLevel.ALL
//    }
    install(JsonFeature) {
        serializer = GsonSerializer()
    }
    defaultRequest {
        header("Authorization", "Bearer ${sConf.access_token}")
    }
}

suspend fun getYLibraryAlbums(): YResponse<List<YAlbum>> =
    ymClient.get("https://api.music.yandex.net/users/${yConf.userId}/likes/albums")

suspend fun getYAlbumWithTracks(id: Int): YResponse<YAlbum> =
    ymClient.get("https://api.music.yandex.net/albums/$id/with-tracks")

suspend fun searchSAlbum(artist: String, album: String): SAlbumsResponse =
    sClient.get(
        "https://api.spotify.com/v1/search?type=album&q=${
            URLEncoder.encode(
                "$artist $album",
                "utf-8"
            )
        }"
    )

suspend fun searchSArtist(artist: String): SArtistResponse =
    sClient.get(
        "https://api.spotify.com/v1/search?type=artist&q=${
            URLEncoder.encode(
                artist,
                "utf-8"
            )
        }"
    )

suspend fun getSAlbums(artist: SArtist, offset: Int = 0): Items<SAlbum> =
    sClient.get(
        "https://api.spotify.com/v1/artists/${artist.id}/albums?include_groups=album,single&limit=50&offset=$offset"
    )

suspend fun followArtist(ids: List<String>) {
    mutexLike.withLock {
        sClient.put<Any>("https://api.spotify.com/v1/me/following") {
            parameter("ids", ids.joinToString(","))
            parameter("type", "artist")
        }
        delay(delayForLikes)
    }
}

suspend fun addAlbumsToLibrary(trackIds: List<String>) {
    mutexLike.withLock {
        sClient.put<Any>("https://api.spotify.com/v1/me/albums") {
            parameter("ids", trackIds.joinToString(","))
        }
        delay(delayForLikes)
    }
}


fun normalizeArtist(artist: String): String {
    return normalizeBasic(artist)
}

fun normalizeAlbum(album: String): String {
    return normalizeBasic(album)
        .replace("[0-9\\s]{4,}remastered\\s+version".toRegex(), "")
        .replace("remastered\\s+[0-9]{4}".toRegex(), "")
        .replace("[0-9\\s]{4,}remaster".toRegex(), "")
        .replace("remastered", "")
        .replace("remaster", "")
        .replace("deluxe version", "")
        .trim()
}

fun normalizeBasic(s: String): String = s.lowercase().replace("-", " ").replace("(^the\\s)|(\\sthe\\s)".toRegex(), " ")
    .replace("[^\\w\\s\\-а-яё\\u0080-\\u00FF]+".toRegex(), "").replace("\\s+".toRegex(), " ").trim()


fun compareStrings(f: String, s: String, tag: String = ""): Boolean {
    if (f == s) {
        return true
    }

    if (inCyrillicRegex.matches(f) || inCyrillicRegex.matches(s)) {
        val fT = if (inCyrillicRegex.matches(f)) toLatinTrans.transliterate(f) else f
        val sT = if (inCyrillicRegex.matches(s)) toLatinTrans.transliterate(s) else s
        return fT == sT
    }
    return false
}

suspend fun import() = withContext(Dispatchers.IO) {
    val albumIds = getYLibraryAlbums().result.map { it.id }
    //val albumIds = listOf(297720)
    val jobs: MutableList<Job> = mutableListOf()
    val yLibrary: MutableMap<String, List<YAlbum>> = mutableMapOf()
    val mutex = Mutex()
    albumIds.forEach { id ->
        jobs.add(launch {
            fetchYandexAlbum(id, mutex, yLibrary)
        })
    }
    jobs.joinAll()

    importLibrary(yLibrary)
}

suspend fun importLibrary(yLibrary: MutableMap<String, List<YAlbum>>) = withContext(Dispatchers.IO) {
    val forImport = mutableListOf<Pair<String, SAlbum>>()
    val forLikes = mutableListOf<SArtist>()
    val cantImport = mutableListOf<Pair<String, YAlbum>>()
    yLibrary.forEach { (yArtist, yAlbums) ->
        val artist = searchSArtist(yArtist).artists.items.firstOrNull {
            compareStrings(yArtist, normalizeArtist(it.name), "artist")
        }
        if (artist == null) {
            printlnColored("Can't find artist for: $yArtist", TextColor.RED)
            return@forEach
        }

        forLikes.add(artist)

        var offset = 0
        var total: Int
        val sAlbums = mutableListOf<SAlbum>()
        val sSingles = mutableListOf<SAlbum>()
        do {
            val sAlbumsAll = getSAlbums(artist, offset)
            sAlbums.addAll(sAlbumsAll.items.filter { it.album_type == "album" })
            sSingles.addAll(sAlbumsAll.items.filter { it.album_type == "single" })
            total = sAlbumsAll.total
            offset += 50
        } while (offset < total)

        yAlbums.forEach { yAlbum ->
            val sAlbum =
                sAlbums.firstOrNull { compareStrings(normalizeAlbum(yAlbum.title), normalizeAlbum(it.name), "album") }
                    ?: sSingles.firstOrNull {
                        compareStrings(
                            normalizeAlbum(yAlbum.title),
                            normalizeAlbum(it.name),
                            "single"
                        )
                    }
            if (sAlbum != null) {
                forImport.add(Pair(yArtist, sAlbum))
            } else {
                cantImport.add(Pair(yArtist, yAlbum))
            }
        }
    }

    val ids = mutableListOf<String>()
    forImport.forEach { pair ->
        printlnColored(
            "Import album `${pair.first} - ${pair.second.name}` with id `${pair.second.id}` ${pair.second.type}",
            TextColor.PURPLE
        )
        ids.add(pair.second.id)
        if (ids.count() == 20) {
            addAlbumsToLibrary(ids)
            ids.clear()
        }
    }

    if (ids.isNotEmpty()) {
        addAlbumsToLibrary(ids)
        ids.clear()
    }

    forLikes.forEach { artist ->
        printlnColored(
            "Follow artist `${artist.name}",
            TextColor.BLUE
        )
        ids.add(artist.id)
        if (ids.count() == 20) {
            followArtist(ids)
            ids.clear()
        }
    }

    if (ids.isNotEmpty()) {
        followArtist(ids)
        ids.clear()
    }

    cantImport.forEach { pair ->
        printlnColored(
            "Can't import album `${pair.first} - ${pair.second.title}` with id `${pair.second.id}`",
            TextColor.RED
        )
    }
}

suspend fun fetchYandexAlbum(id: Int, mutex: Mutex, yLibrary: MutableMap<String, List<YAlbum>>) =
    withContext(Dispatchers.IO) {
        val yAlbum = getYAlbumWithTracks(id).result
        if (yAlbum.metaType != "music") return@withContext
        var yArtist: String? = null
        yAlbum.artists.firstOrNull()?.let {
            yArtist = normalizeArtist(it.name)
        }
        yArtist?.let { yArtist ->
            mutex.withLock {
                val albums = (yLibrary[yArtist] ?: emptyList()).toMutableList()
                albums.add(yAlbum)
                yLibrary.put(yArtist, albums.toList())
            }
        }
    }

sealed class Action {
    object Import : Action()
}

var action: Action = Action.Import

runBlocking {
    when (val a = action) {
        is Action.Import -> import()
        else -> Unit
    }
}


fun printlnColored(s: String, color: TextColor) {
    println("${color.value}$s${TextColor.RESET.value}")
}


data class YConf(val csvPath: String, val userId: String, val token: String)
data class SConf(val access_token: String)

data class YResponse<T>(val result: T)

data class YAlbum(
    val id: Int,
    val title: String,
    val version: String?,
    val artists: List<YArtist>,
    val volumes: List<List<YTrack>>,
    val metaType: String
)

data class YTrack(val id: Int, val title: String)
data class YArtist(val id: Int, val name: String)

data class SArtistResponse(
    val artists: Items<SArtist>
)

data class SAlbumsResponse(
    val albums: Items<SAlbum>
)

data class Items<T>(
    val items: List<T>,
    val total: Int,
    val offset: Int
)

data class SAlbum(
    val id: String,
    val album_type: String,
    val name: String,
    val release_date: String,
    val total_tracks: Int,
    val type: String,
    val artists: List<SArtist>,
)

data class SArtist(
    val id: String,
    val name: String,
    val type: String
)

enum class TextColor(val value: String) {
    RESET("\u001B[0m"), BLACK("\u001B[30m"), RED("\u001B[31m"), GREEN("\u001B[32m"), YELLOW("\u001B[33m"), BLUE("\u001B[34m"), PURPLE(
        "\u001B[35m"
    ),
    CYAN("\u001B[36m"), WHITE("\u001B[37m"),
}
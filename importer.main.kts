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
import me.xdrop.fuzzywuzzy.FuzzySearch
import java.io.File
import java.net.URLEncoder

val csvPath = "ytm.csv"
val token = "XXXXXXXXXXXX"
val userId = "XXXXXXXXXXXX"
val minFuzzyRatio = 96
val delayForLikes = 100L
val mutex = Mutex()
val mutexLike = Mutex()
var result = Result()
val toLatinTrans: Transliterator = Transliterator.getInstance("Any-Latin; NFD;")
val inCyrillicRegex = ".*\\p{InCyrillic}.*".toRegex()
val inNonCyrillicRegex = ".*[^\\p{InCyrillic}].*".toRegex()

val client = HttpClient {
    install(Logging) {
        logger = object : Logger {
            override fun log(message: String) {
                //println(message)
            }
        }
        level = LogLevel.ALL
    }
    install(JsonFeature) {
        serializer = GsonSerializer()
    }
    defaultRequest {
        header("Authorization", "OAuth $token")
    }
}

fun printlnColored(s: String, color: TextColor) {
    println("${color.value}$s${TextColor.RESET.value}")
}

fun getDataForImport(csvPath: String): Map<RawArtist, List<RawAlbum>> {
    val output: MutableMap<String, List<RawAlbum>> = mutableMapOf()
    File(csvPath).readLines().forEach { line ->
        val list: List<String> = line.split(",(?=([^\"]*\"[^\"]*\")*[^\"]*$)".toRegex())
        output[list[3]] = (output[list[3]] ?: emptyList()) + stringToRawAlbum(list[2], list[3])
    }

    return output.mapKeys {
        stringToRawArtist(it.key)
    }.mapValues { it.value.distinctBy { album -> album.rawTitle } }
}

fun stringToRawArtist(str: String): RawArtist = RawArtist(
    name = normalizeArtist(str), rawName = str, tName = normalizeArtist(toLatinTrans.transliterate(str))
)

fun stringToRawAlbum(str: String, artist: String = "", id: Int? = null): RawAlbum {
    val versions =
        ("\\(.*?\\)".toRegex().find(str)?.groupValues ?: emptyList()) + ("\\[.*?\\]".toRegex().find(str)?.groupValues
            ?: emptyList())

    val isDeluxe = versions.any { it.contains("deluxe", ignoreCase = true) }

    val year = if (versions.any { it.contains("remaster", ignoreCase = true) }) {
        versions.firstOrNull { it.contains("[0-9]{4}".toRegex()) }?.let { version ->
            try {
                "[0-9]{4}".toRegex().find(version)?.groupValues?.first()?.toInt()
            } catch (ex: Exception) {
                null
            }
        }
    } else null

    var title = str
    versions.forEach {
        title = title.replace(it, "")
    }

    return RawAlbum(
        title = normalizeAlbumTitle(title),
        versions = versions.map { normalizeBasic(it) },
        isDeluxe = isDeluxe,
        remasterYear = year,
        rawTitle = str,
        artist = artist,
        id = id
    )
}

suspend fun searchArtist(artist: String, lang: String = "ru-RU"): Response<SearchArtistsResults> =
    client.get("https://api.music.yandex.net/search") {
        parameter("text", artist)
        parameter("type", "artist")
        parameter("page", "0")
        header("Accept-Language", lang)
    }

suspend fun getArtistAlbums(id: Int): Response<ArtistAlbums> =
    client.get("https://api.music.yandex.net/artists/$id/direct-albums")

suspend fun likeAlbum(albumIds: List<Int>) {
    mutexLike.withLock {
        client.post<Response<Any>>("https://api.music.yandex.net/users/$userId/likes/albums/add-multiple") {
            parameter("album-ids", albumIds.joinToString(","))
        }
        delay(delayForLikes)
    }
}

suspend fun likeArtist(artistIds: List<Int>) {
    mutexLike.withLock {
        client.submitForm<Response<Any>>("https://api.music.yandex.net/users/$userId/likes/artists/add-multiple") {
            parameter("artist-ids", artistIds.joinToString(","))
        }
        delay(delayForLikes)
    }
}

suspend fun processArtist(artist: RawArtist, albums: List<RawAlbum>) {
    printlnColored("Process artist: ${artist.rawName}", TextColor.YELLOW)

    var foundedArtist: Artist? = null
    for (lang in listOf("ru-RU", "en-US")) {
        val searchArtistResponse = try {
            searchArtist(artist.name, lang = lang)
        } catch (ex: Exception) {
            printlnColored(
                "Can't found artist '${artist.rawName} (normalized: ${artist.name})'. Cause: ${ex.message}",
                TextColor.RED
            )
            null
        }

        foundedArtist = searchArtistResponse?.result?.artists?.results?.find {
            val n = normalizeArtist(it.name)
            val t = toLatinTrans.transliterate(n)
            FuzzySearch.weightedRatio(n, artist.name) >= minFuzzyRatio || FuzzySearch.weightedRatio(
                n,
                artist.tName
            ) >= minFuzzyRatio || FuzzySearch.weightedRatio(
                t,
                artist.name
            ) >= minFuzzyRatio || FuzzySearch.weightedRatio(t, artist.tName) >= minFuzzyRatio
        }
        if (foundedArtist != null) break
    }

    // Try to use the first results rom artist search
    if (foundedArtist == null) {
        foundedArtist = try {
            searchArtist(artist.name)
        } catch (ex: Exception) {
            printlnColored(
                "Can't found artist '${artist.rawName} (normalized: ${artist.name})'. Cause: ${ex.message}",
                TextColor.RED
            )
            null
        }?.result?.artists?.results?.first()
    }

    if (foundedArtist != null) {
        val albumsToLike: MutableList<RawAlbum> = mutableListOf()
        val foundedAlbums = getArtistAlbums(foundedArtist.id).result.albums.map {
            stringToRawAlbum(str = it.title, id = it.id)
        }
        val notFounded = albums.filter { album ->

            val foundAlbum = foundedAlbums.find {
                it.title == album.title
            }

            if (foundAlbum?.id != null) {
                albumsToLike.add(foundAlbum)
            }
            foundAlbum == null
        }

        val validated = albumsToLike.mapNotNull { it.id }
        if (validated.isNotEmpty()) {
            try {
                likeArtist(listOf(foundedArtist.id))
                printlnColored("Like artist: ${foundedArtist.name}", TextColor.CYAN)
            } catch (ex: Exception) {
                printlnColored(
                    "Can't like artist '${foundedArtist.name} (id: ${foundedArtist.id})'. Cause: ${ex.message}",
                    TextColor.RED
                )
            }

            try {
                likeAlbum(validated)
                albumsToLike.filter { it.id != null }.forEach {
                    //Like album
                    printlnColored("Like album: ${foundedArtist.name} - ${it.title}", TextColor.PURPLE)
                }
            } catch (ex: Exception) {
                printlnColored(
                    "Can't like albums $validated. Cause: ${ex.message}",
                    TextColor.RED
                )
            }

        }
        saveResult(
            foundedAlbums = albums.size - notFounded.size, notFoundedArtist = null, notFoundedAlbums = notFounded
        )

    } else saveResult(
        foundedAlbums = 0, notFoundedArtist = artist.rawName, notFoundedAlbums = albums
    )
}

suspend fun saveResult(foundedAlbums: Int, notFoundedArtist: String?, notFoundedAlbums: List<RawAlbum>) {
    mutex.withLock {
        result = result.copy(foundArtists = when (notFoundedArtist) {
            null -> result.foundArtists + 1
            else -> result.foundArtists
        },
            foundAlbums = result.foundAlbums + foundedAlbums,
            notFoundArtists = notFoundedArtist?.let { result.notFoundArtists + listOf(it) } ?: result.notFoundArtists,
            notFoundedAlbums = result.notFoundedAlbums + notFoundedAlbums)
    }
}


fun normalizeAlbumTitle(title: String): String {
    val nTitle = normalizeBasic(title)
    return if (inCyrillicRegex.matches(nTitle) && inNonCyrillicRegex.matches(nTitle)) {
        nTitle.replace("x", "х").replace("c", "с")
    } else nTitle
}

fun normalizeArtist(artist: String): String {
    return normalizeBasic(artist)
}

fun normalizeBasic(s: String): String = s.lowercase().replace("-", " ").replace("(^the\\s)|(\\sthe\\s)".toRegex(), " ")
    .replace("[^\\w\\s\\-а-яё\\u0080-\\u00FF]+".toRegex(), "").replace("\\s+".toRegex(), " ").trim()

runBlocking {
    val input = getDataForImport(csvPath)
    input.forEach { (t, u) ->
        u.forEach {
            println("${it.rawTitle} -> ${t.rawName}")
        }
    }
    val jobs: MutableList<Job> = mutableListOf()

    input.filter { it.key.name.isNotBlank() && it.value.isNotEmpty() }.forEach { (artist, albums) ->
        jobs.add(launch(Dispatchers.IO) {
            processArtist(artist = artist, albums = albums)
        })
    }

    jobs.joinAll()

    printlnColored("Found artist/albums: ${result.foundArtists}/${result.foundAlbums}", TextColor.GREEN)
    if (result.notFoundArtists.isNotEmpty()) {
        printlnColored("Not found artists: ${result.notFoundArtists.size}", TextColor.RED)
        result.notFoundArtists.forEach {
            printlnColored(
                "\t$it (https://music.yandex.ru/search?text=${URLEncoder.encode(it, "utf-8")}&type=artist)\n",
                TextColor.PURPLE
            )
        }
    }
    if (result.notFoundedAlbums.isNotEmpty()) {
        printlnColored("Not found albums: ${result.notFoundedAlbums.size}", TextColor.RED)
        result.notFoundedAlbums.sortedBy { it.artist }.forEach {
            printlnColored(
                "\t${it.artist} - ${it.rawTitle} (https://music.yandex.ru/search?text=${
                    URLEncoder.encode(
                        "${it.rawTitle} ${it.artist}", "utf-8"
                    )
                }&type=albums)\n", TextColor.PURPLE
            )
        }
    }
}

data class Response<T>(val result: T)
data class SearchArtistsResults(val artists: Artists?)
data class Artists(val results: List<Artist>)
data class ArtistAlbums(val albums: List<Album>)

data class SearchAlbumsResults(val albums: Albums?)
data class Albums(val results: List<Album>)
data class Album(val id: Int, val title: String, val version: String?, val artists: List<Artist>)
data class Artist(val id: Int, val name: String)
data class RawArtist(val name: String, val tName: String, val rawName: String)
data class RawAlbum(
    val title: String,
    val versions: List<String>,
    val remasterYear: Int?,
    val isDeluxe: Boolean,
    val rawTitle: String,
    val artist: String,
    val id: Int? = null
)

data class Result(
    val foundArtists: Int = 0,
    val foundAlbums: Int = 0,
    val notFoundArtists: List<String> = emptyList(),
    val notFoundedAlbums: List<RawAlbum> = emptyList()
)

enum class TextColor(val value: String) {
    RESET("\u001B[0m"),
    BLACK("\u001B[30m"),
    RED("\u001B[31m"),
    GREEN("\u001B[32m"),
    YELLOW("\u001B[33m"),
    BLUE("\u001B[34m"),
    PURPLE("\u001B[35m"),
    CYAN("\u001B[36m"),
    WHITE("\u001B[37m"),
}
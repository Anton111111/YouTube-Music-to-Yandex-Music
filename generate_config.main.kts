#!/usr/bin/env kotlin

@file:Repository("https://repo1.maven.org/maven2")
@file:DependsOn("io.ktor:ktor-client-core-jvm:1.6.8")
@file:DependsOn("io.ktor:ktor-client-cio-jvm:1.6.8")
@file:DependsOn("io.ktor:ktor-client-auth-jvm:1.6.8")
@file:DependsOn("io.ktor:ktor-client-logging-jvm:1.6.8")
@file:DependsOn("io.ktor:ktor-client-json-jvm:1.6.8")
@file:DependsOn("io.ktor:ktor-client-gson:1.6.8")


import io.ktor.client.*
import io.ktor.client.features.json.*
import io.ktor.client.features.logging.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import kotlinx.coroutines.*
import java.io.File


val client = HttpClient {
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
}

suspend fun login(username: String, password: String): Token =
    client.post("https://oauth.yandex.ru/token") {
        body = FormDataContent(Parameters.build {
            append("grant_type", "password")
            append("client_id", "23cabbbdc6cd418abb4b39c32c41195d")
            append("client_secret", "53bc75238f0c4d08a118e51fe9203300")
            append("username", username)
            append("password", password)
        })
    }

suspend fun accountStatus(token: Token): Response<AccountWrapper> =
    client.get("https://api.music.yandex.net/account/status") {
        header("Authorization", "OAuth ${token.access_token}")
    }


runBlocking {
    val username = args[0]
    val password = args[1]
    val csvPath = args[2]

    if (!File(csvPath).exists()) {
        println("CSV is not exists")
        return@runBlocking
    }
    //val token = login(username, password)
    val token = Token(access_token = "AQAAAAATMT22AAG8XqwA7ckQP0h1jgk2AAfAdsk")
    if (token.access_token.isBlank()) {
        println("Can't get token")
        return@runBlocking
    }
    println("Token: ${token.access_token}")
    //val account = accountStatus(token).result.account
    val account = Account(login = "anton.sergeevich.potekhin")
    if (account.login.isBlank()) {
        println("Can't get login")
        return@runBlocking
    }
    println("User Id: ${account.login}")
    val jsonExample = File("config.json.example").readText()
    File("config.json").writeText(
        jsonExample
            .replace("<csv_path>", csvPath)
            .replace("<user_id>", account.login)
            .replace("<token>", password)
    )
}

data class Response<T>(val result: T)
data class AccountWrapper(val account: Account)
data class Account(val login: String)
data class Token(val access_token: String)

package net.deamjava.fabri_auth.auth

import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID


object PremiumManager {

    private val httpClient: HttpClient = HttpClient.newHttpClient()
    fun fetchMojangUuid(username: String): UUID? {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.mojang.com/users/profiles/minecraft/$username"))
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() == 200) {
                val json = JsonParser.parseString(response.body()).asJsonObject
                val idStr = json.get("id").asString
                UUID.fromString(
                    idStr.replaceFirst(
                        "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})".toRegex(),
                        "$1-$2-$3-$4-$5"
                    )
                )
            } else {
                null
            }
        } catch (e: Exception) {
            println("[FabriAuth] Mojang API lookup failed for '$username': ${e.message}")
            null
        }
    }

    fun isKnownPremiumUuid(uuid: java.util.UUID): Boolean {
        return AuthStateManager.isPremium(uuid)
    }

    fun offlineUuid(username: String): UUID {
        return UUID.nameUUIDFromBytes("OfflinePlayer:$username".toByteArray(Charsets.UTF_8))
    }
}
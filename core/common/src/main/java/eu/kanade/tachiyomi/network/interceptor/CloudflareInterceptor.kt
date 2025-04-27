// File: CloudflareInterceptor.kt
package eu.kanade.tachiyomi.network.interceptor

import android.content.Context
import okhttp3.Cookie
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.network.NetworkPreferences
import kotlinx.serialization.json.*
import java.io.IOException
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

class CloudflareInterceptor(
    private val context: Context,
    private val cookieManager: AndroidCookieJar,
    private val defaultUserAgentProvider: () -> String,
    private val networkPreferences: NetworkPreferences
) : WebViewInterceptor(context, defaultUserAgentProvider) {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override fun shouldIntercept(response: Response): Boolean {
        return response.code in ERROR_CODES && response.header("Server") in SERVER_CHECK
    }

    override fun intercept(chain: Interceptor.Chain, request: Request, response: Response): Response {
        response.close()
        // Clear any previous clearance cookies
        cookieManager.remove(request.url, COOKIE_NAMES, 0)

        // Build cookies array
        val cookieArray = JsonArray(
            cookieManager
                .get(request.url)
                .map { cookie ->
                    buildJsonObject {
                        put("name", cookie.name)
                        put("value", cookie.value)
                    }
                }
        )

        // Build payload
        val payloadJson = buildJsonObject {
            put("cmd", "request.get")
            put("url", request.url.toString())
            put("userAgent", defaultUserAgentProvider())
            put("cookies", cookieArray)
        }
        val payloadString = payloadJson.toString()

        // Read endpoint from prefs instead of hard-coding
        val endpoint = networkPreferences.flaresolverrEndpoint().get()
        val flRequest = Request.Builder()
            .url(endpoint)
            .post(payloadString.toRequestBody(jsonMediaType))
            .build()

        val flResponse = chain.proceed(flRequest)
        if (!flResponse.isSuccessful) {
            flResponse.close()
            throw IOException("Flaresolverr request failed with HTTP ${flResponse.code}")
        }

        val bodyString = flResponse.body?.string()
            ?: throw IOException("Empty body from Flaresolverr")
        val solution = parseFlaresolverrResponse(bodyString)

        // Save any new cookies
        solution.cookies.forEach { cookieStr ->
            Cookie.parse(request.url, cookieStr)
                ?.let { parsed -> cookieManager.saveFromResponse(request.url, listOf(parsed)) }
        }

        // Re-issue the original request against the (possibly updated) URL
        return chain.proceed(
            request.newBuilder()
                .url(solution.url)
                .build()
        )
    }

    private fun parseFlaresolverrResponse(responseBody: String): Solution {
        val root = jsonParser.parseToJsonElement(responseBody).jsonObject

        if (root["status"]?.jsonPrimitive?.content != "ok") {
            val msg = root["message"]?.jsonPrimitive?.content ?: "unknown error"
            throw IOException("Flaresolverr error: $msg")
        }

        val sol = root["solution"]?.jsonObject
            ?: throw IOException("No solution object in Flaresolverr response")

        val url = sol["url"]?.jsonPrimitive?.content
            ?: throw IOException("No URL in solution")

        val cookies = sol["cookies"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: emptyList()

        return Solution(url, cookies)
    }

    private data class Solution(val url: String, val cookies: List<String>)
}

private val ERROR_CODES = listOf(403, 503)
private val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare")
private val COOKIE_NAMES = listOf("cf_clearance")

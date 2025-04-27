package eu.kanade.tachiyomi.network.interceptor

import android.content.Context
import android.widget.Toast
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.util.system.toast
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.io.IOException
import java.util.concurrent.CountDownLatch

class CloudflareInterceptor(
    private val context: Context,
    private val cookieManager: AndroidCookieJar,
    defaultUserAgentProvider: () -> String,
) : WebViewInterceptor(context, defaultUserAgentProvider) {

    private val flaresolverrEndpoint = "https://flaresolverr.e-nes.eu/v1"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override fun shouldIntercept(response: Response): Boolean {
        // Check if Cloudflare anti-bot is on
        return response.code in ERROR_CODES && response.header("Server") in SERVER_CHECK
    }

    override fun intercept(chain: Interceptor.Chain, request: Request, response: Response): Response {
        try {
            response.close()
            cookieManager.remove(request.url, COOKIE_NAMES, 0)
            
            // Build Flaresolverr request
            val payload = """
                {
                    "cmd": "request.get",
                    "url": "${request.url}",
                    "userAgent": "${defaultUserAgentProvider()}",
                    "cookies": [${getCookiesForRequest(request)}]
                }
            """.trimIndent()

            val flaresolverrRequest = Request.Builder()
                .url(flaresolverrEndpoint)
                .post(payload.toRequestBody(jsonMediaType))
                .build()

            // Execute Flaresolverr request
            val flaresolverrResponse = chain.proceed(flaresolverrRequest)
            if (!flaresolverrResponse.isSuccessful) {
                throw IOException("Flaresolverr request failed: ${flaresolverrResponse.code}")
            }

            val responseBody = flaresolverrResponse.body?.string()
            val solution = parseFlaresolverrResponse(responseBody)
            
            // Update cookies from the solution
            solution.cookies.forEach { cookie ->
                cookieManager.saveFromResponse(
                    request.url, 
                    listOf(Cookie.parse(request.url, cookie)!!)
                )
            }

            return chain.proceed(request.newBuilder().url(solution.url).build())
        } catch (e: Exception) {
            throw IOException(context.stringResource(MR.strings.information_cloudflare_bypass_failure), e)
        }
    }

    private fun getCookiesForRequest(request: Request): String {
        return cookieManager.get(request.url).joinToString(",") { 
            "{\"name\":\"${it.name}\",\"value\":\"${it.value}\"}" 
        }
    }

    private fun parseFlaresolverrResponse(responseBody: String?): Solution {
        val json = responseBody?.let { jsonParser.parseJson(it).jsonObject } ?: throw IOException("Empty response from Flaresolverr")
        
        if (json["status"]?.jsonPrimitive?.content != "ok") {
            throw IOException("Flaresolverr error: ${json["message"]?.jsonPrimitive?.content}")
        }

        val solution = json["solution"]?.jsonObject ?: throw IOException("No solution in response")
        return Solution(
            url = solution["url"]?.jsonPrimitive?.content ?: throw IOException("No URL in solution"),
            cookies = solution["cookies"]?.jsonArray?.mapNotNull { cookie ->
                cookie.jsonObject.let { 
                    "${it["name"]?.jsonPrimitive?.content}=${it["value"]?.jsonPrimitive?.content}" 
                }
            } ?: emptyList()
        )
    }

    private data class Solution(val url: String, val cookies: List<String>)
}

private val ERROR_CODES = listOf(403, 503)
private val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare")
private val COOKIE_NAMES = listOf("cf_clearance")


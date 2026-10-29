package com.deepline.privateapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object DeepSeek {
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()
    suspend fun reply(apiKey: String, system: String, history: JSONArray): String = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "请先在设置中填写 DeepSeek API Key" }
        val msgs = JSONArray().put(JSONObject().put("role","system").put("content",system))
        for (i in 0 until history.length()) msgs.put(history.getJSONObject(i))
        val body = JSONObject()
            .put("model", "deepseek-flash")
            .put("messages", msgs)
            .put("thinking", JSONObject().put("type", "disabled"))
            .put("max_tokens", 2048)
        val request = Request.Builder().url("https://api.deepseek.com/chat/completions")
            .header("Authorization","Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val json = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
            if (!response.isSuccessful) {
                val apiMessage = json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                throw IllegalStateException("DeepSeek 请求失败（HTTP ${response.code}）：${apiMessage ?: raw.take(300).ifBlank { "接口未返回错误详情" }}")
            }
            json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                ?.trim()?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("DeepSeek 请求已成功，但回复内容为空。返回数据：${raw.take(300)}")
        }
    }
}

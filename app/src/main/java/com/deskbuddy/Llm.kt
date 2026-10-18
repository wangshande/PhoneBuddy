package com.deskbuddy

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 可选的对话模型服务商。
 * Claude 使用原生 Messages 接口，其余都走 OpenAI 兼容的 chat/completions 接口。
 */
enum class Provider(
    val title: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val anthropic: Boolean,
    val hint: String
) {
    CLAUDE("Claude", "https://api.anthropic.com", "claude-haiku-5-5", true,
        "在 Anthropic 控制台获取密钥"),
    QWEN("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", false,
        "在阿里云百炼获取密钥，中文对话比较自然"),
    GPT("GPT", "https://api.openai.com/v1", "gpt-4o-mini", false,
        "在 OpenAI 平台获取密钥"),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", false,
        "在 DeepSeek 开放平台获取密钥"),
    CUSTOM("自定义", "", "", false,
        "填写任意 OpenAI 兼容服务的地址、模型名和密钥");

    companion object {
        fun from(name: String?): Provider = values().firstOrNull { it.name == name } ?: CLAUDE
    }
}

/** 一套对话模型配置。密钥只保存在本机。 */
data class ModelConfig(
    val provider: Provider,
    val baseUrl: String,
    val model: String,
    val key: String
) {
    val isReady: Boolean
        get() = key.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()
}

/** 对话模型的保存与读取。每个服务商各自保存一套配置，切换时不丢。 */
object AiPrefs {
    private const val FILE = "ai"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun selected(ctx: Context): Provider = try {
        Provider.from(prefs(ctx).getString("provider", null))
    } catch (e: Exception) {
        Provider.CLAUDE
    }

    fun config(ctx: Context, p: Provider = selected(ctx)): ModelConfig = try {
        val sp = prefs(ctx)
        ModelConfig(
            provider = p,
            baseUrl = sp.getString("url_${p.name}", null)?.takeIf { it.isNotBlank() } ?: p.defaultBaseUrl,
            model = sp.getString("model_${p.name}", null)?.takeIf { it.isNotBlank() } ?: p.defaultModel,
            key = sp.getString("key_${p.name}", "").orEmpty()
        )
    } catch (e: Exception) {
        ModelConfig(p, p.defaultBaseUrl, p.defaultModel, "")
    }

    fun save(ctx: Context, cfg: ModelConfig) {
        try {
            prefs(ctx).edit()
                .putString("provider", cfg.provider.name)
                .putString("url_${cfg.provider.name}", cfg.baseUrl.trim())
                .putString("model_${cfg.provider.name}", cfg.model.trim())
                .putString("key_${cfg.provider.name}", cfg.key.trim())
                .apply()
        } catch (_: Exception) {
        }
    }

    /** 配置完整就用大模型对话，否则退回离线规则。 */
    fun judge(ctx: Context): SemanticJudge {
        val cfg = config(ctx)
        return if (cfg.isReady) LlmJudge(ChatClient(cfg), profile={ PetPrefs.load(ctx) }, memory={q -> if(CompanionPrefs.recallEnabled(ctx)) MemoryStore(ctx).relevant(q) else ""}) else RuleJudge { PetPrefs.load(ctx) }
    }
}

/**
 * 统一的对话调用。阻塞，必须在后台线程使用。
 * messages 为 (role, content) 列表，role 只用 user 或 assistant。
 */
class ChatClient(private val cfg: ModelConfig) {

    fun complete(system: String, messages: List<Pair<String, String>>, maxTokens: Int = 400): String =
        if (cfg.provider.anthropic) anthropic(system, messages, maxTokens)
        else openAiCompatible(system, messages, maxTokens)

    private fun anthropic(system: String, messages: List<Pair<String, String>>, maxTokens: Int): String {
        val body = JSONObject()
            .put("model", cfg.model)
            .put("max_tokens", maxTokens)
            .put("system", system)
            .put("messages", toJsonMessages(messages))
        val resp = post(
            url = "${cfg.baseUrl.trimEnd('/')}/v1/messages",
            headers = mapOf("x-api-key" to cfg.key, "anthropic-version" to "2023-06-01"),
            body = body
        )
        return resp.getJSONArray("content").getJSONObject(0).getString("text")
    }

    private fun openAiCompatible(system: String, messages: List<Pair<String, String>>, maxTokens: Int): String {
        val all = JSONArray()
        all.put(JSONObject().put("role", "system").put("content", system))
        messages.forEach { (r, c) -> all.put(JSONObject().put("role", r).put("content", c)) }
        val body = JSONObject()
            .put("model", cfg.model)
            .put("max_tokens", maxTokens)
            .put("messages", all)
        val resp = post(
            url = "${cfg.baseUrl.trimEnd('/')}/chat/completions",
            headers = mapOf("Authorization" to "Bearer ${cfg.key}"),
            body = body
        )
        return resp.getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
    }

    private fun toJsonMessages(messages: List<Pair<String, String>>): JSONArray {
        val arr = JSONArray()
        messages.forEach { (r, c) -> arr.put(JSONObject().put("role", r).put("content", c)) }
        return arr
    }

    private fun post(url: String, headers: Map<String, String>, body: JSONObject): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 25_000
            doOutput = true
            setRequestProperty("content-type", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val detail = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IllegalStateException("HTTP $code ${detail.take(120)}")
            }
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }
}

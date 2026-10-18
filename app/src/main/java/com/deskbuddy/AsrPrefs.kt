package com.deskbuddy
import android.content.Context
import java.net.URI

data class AsrConfig(val external: Boolean, val endpoint: String, val key: String, val model: String) {
    val error: String? get() = when {
        !external -> null
        runCatching { URI(endpoint).scheme != "https" || URI(endpoint).host.isNullOrBlank() }.getOrDefault(true) -> "请填写 HTTPS 语音转录接口地址"
        model.isBlank() -> "请填写语音识别模型名"
        else -> null
    }
}
object AsrPrefs {
    private fun prefs(ctx:Context) = ctx.getSharedPreferences("speech_engine",Context.MODE_PRIVATE)
    fun config(ctx:Context):AsrConfig { val p=prefs(ctx);return AsrConfig(p.getBoolean("external",false),p.getString("endpoint","").orEmpty(),p.getString("key","").orEmpty(),p.getString("model","").orEmpty()) }
    fun save(ctx:Context,c:AsrConfig) { prefs(ctx).edit().putBoolean("external",c.external).putString("endpoint",c.endpoint.trim()).putString("key",c.key.trim()).putString("model",c.model.trim()).apply() }
    fun engine(ctx:Context,lowPower:Boolean=false):SpeechEngine = config(ctx).let { if(it.external) CloudAsr(it) else VoskAsr(lowPower) }
}

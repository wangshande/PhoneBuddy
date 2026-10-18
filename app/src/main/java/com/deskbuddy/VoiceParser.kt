package com.deskbuddy

/**
 * 把一句语音解析成意图。
 * 当前是规则版本（离线、零成本），后续可替换为大模型做更精细的拆分。
 */
sealed class VoiceIntent {
    data class AddTodos(val items: List<String>) : VoiceIntent()
    data class CompleteTodo(val hint: String) : VoiceIntent()
    object Unknown : VoiceIntent()
}

object VoiceParser {

    private val doneWords = listOf("完成了", "做完了", "搞定了", "弄完了", "已经完成", "已完成")
    private val addSeparators = Regex("[，,。；;、]|然后|还要|还有|另外|以及")
    private val addPrefixes = Regex("^(?:请)?(?:帮我)?(?:记一下|记下|记住|提醒我|待办[：:]?|要做的是)")

    fun parse(raw: String): VoiceIntent {
        val text = raw.trim()
        if (text.isEmpty()) return VoiceIntent.Unknown

        // 离线兜底只接显式指令；普通聊天绝不能全被解析为待办。
        if (Regex("^(他说|她说|他们说|她们说|同事说|老板说)").containsMatchIn(text)) return VoiceIntent.Unknown
        val done = doneWords.firstOrNull { text.contains(it) }
        if (done != null) {
            val hint = text.replace(done, "").trim()
            return VoiceIntent.CompleteTodo(hint)
        }

        if (!addPrefixes.containsMatchIn(text)) return VoiceIntent.Unknown

        val items = text
            .replace(addPrefixes, "")
            .split(addSeparators)
            .map { it.trim() }
            .filter { it.length >= 2 }

        return if (items.isEmpty()) VoiceIntent.Unknown else VoiceIntent.AddTodos(items)
    }
}

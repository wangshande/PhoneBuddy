package com.deskbuddy
/** Vosk 中文分词空格仅用于模型输出；屏幕和意图判断使用自然中文，英文词间空格保留。 */
object SpeechText {
    private val betweenChinese = Regex("(?<=[\\u4e00-\\u9fff])\\s+(?=[\\u4e00-\\u9fff])")
    fun normalize(text: String): String = text.replace(betweenChinese, "").trim()
}

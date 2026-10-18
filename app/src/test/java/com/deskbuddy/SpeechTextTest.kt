package com.deskbuddy
import org.junit.Assert.*
import org.junit.Test
class SpeechTextTest {
    @Test fun chineseSpeechBecomesReadableAndActionable() {
        val text = SpeechText.normalize("帮 我 记 下 明天 买 牛奶")
        assertEquals("帮我记下明天买牛奶",text)
        assertEquals(listOf("明天买牛奶"),(VoiceParser.parse(text) as VoiceIntent.AddTodos).items)
    }
    @Test fun englishWordSpacingSurvives() { assertEquals("buy some milk", SpeechText.normalize("buy some milk")) }
}

package com.deskbuddy

import org.junit.Assert.*
import org.junit.Test

class CompanionIntentTest {
    @Test fun feelingsAreAcknowledgedWithoutCreatingTasks() {
        for (text in listOf("我好累", "今天好累啊", "我有点难过", "谢谢你")) {
            val result = RuleJudge().judge(text, emptyList())
            assertTrue(text, result.addressed)
            assertFalse(text, result.isAction)
            assertFalse(text, result.reply.isNullOrBlank())
        }
    }
    @Test fun ordinaryConversationDoesNotBecomeATodo() {
        for (text in listOf("老板说明天开会", "那个人正在跟同事聊天", "这杯咖啡挺好喝", "他说买牛奶完成了")) {
            val result = RuleJudge().judge(text, emptyList())
            assertFalse(text, result.addressed)
            assertFalse(text, result.isAction)
        }
    }
    @Test fun explicitTaskIsCleanAndRequiresConfirmation() {
        val result = RuleJudge().judge("帮我记下买牛奶，还要浇花", emptyList())
        assertEquals(listOf("买牛奶", "浇花"), result.addTodos)
        assertTrue(result.confidence < 0.75f)
    }
    @Test fun completionStillWorks() {
        val result = RuleJudge().judge("买牛奶做完了", emptyList())
        assertEquals("买牛奶", result.completeHint)
        assertTrue(result.confidence < 0.75f)
    }
    @Test fun eachCharacterIntroducesItsOwnName() {
        for (style in PetStyle.values()) {
            assertTrue(RuleJudge { style }.judge("你叫什么", emptyList()).reply!!.contains(style.label))
            assertEquals(style.greeting, RuleJudge { style }.judge("你好", emptyList()).reply)
        }
    }
}

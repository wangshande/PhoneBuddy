package com.deskbuddy

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Todo(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
    var done: Boolean = false,
    var doneAt: Long? = null
)

/**
 * 待办存储。数据只保存在手机本地 SharedPreferences 中。
 */
class TodoStore(context: Context) {

    private val prefs = context.getSharedPreferences("todos", Context.MODE_PRIVATE)

    fun all(): List<Todo> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Todo(
                id = o.getString("id"),
                text = o.getString("text"),
                createdAt = o.getLong("createdAt"),
                done = o.getBoolean("done"),
                doneAt = if (o.isNull("doneAt")) null else o.getLong("doneAt")
            )
        }
    }

    fun pending(): List<Todo> = all().filter { !it.done }

    fun add(text: String): Todo {
        val todo = Todo(text = text)
        save(all() + todo)
        return todo
    }

    /**
     * 根据用户说的话找到最匹配的未完成待办并标记完成。
     * 匹配规则：待办文字与语句共享的字符数最多且超过阈值。
     */
    fun completeBestMatch(spoken: String): Todo? {
        val items = all().toMutableList()
        val candidates = items.filter { !it.done }
        val best = candidates.maxByOrNull { similarity(it.text, spoken) } ?: return null
        if (similarity(best.text, spoken) < MATCH_THRESHOLD) return null

        val idx = items.indexOfFirst { it.id == best.id }
        items[idx] = items[idx].copy(done = true, doneAt = System.currentTimeMillis())
        save(items)
        return items[idx]
    }

    /** 按 id 批量标记完成，返回实际被完成的待办。 */
    fun completeIds(ids: List<String>): List<Todo> {
        val items = all().toMutableList()
        val done = mutableListOf<Todo>()
        items.forEachIndexed { i, t ->
            if (!t.done && t.id in ids) {
                items[i] = t.copy(done = true, doneAt = System.currentTimeMillis())
                done += items[i]
            }
        }
        save(items)
        return done
    }

    fun complete(id: String): Todo? = completeIds(listOf(id)).firstOrNull()

    fun delete(id: String) {
        save(all().filter { it.id != id })
    }

    private fun save(list: List<Todo>) {
        val arr = JSONArray()
        list.forEach { t ->
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("text", t.text)
                    .put("createdAt", t.createdAt)
                    .put("done", t.done)
                    .put("doneAt", t.doneAt ?: JSONObject.NULL)
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val KEY = "items"
        private const val MATCH_THRESHOLD = 0.3

        /** 字符级 Jaccard 相似度，对中文不需要分词即可工作。 */
        fun similarity(a: String, b: String): Double {
            val sa = a.filter { !it.isWhitespace() && it !in "，。、！？,.!?" }.toSet()
            val sb = b.filter { !it.isWhitespace() && it !in "，。、！？,.!?" }.toSet()
            if (sa.isEmpty() || sb.isEmpty()) return 0.0
            val inter = sa.intersect(sb).size.toDouble()
            return inter / sa.union(sb).size
        }
    }
}

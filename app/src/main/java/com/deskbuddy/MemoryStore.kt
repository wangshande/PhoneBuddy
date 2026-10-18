package com.deskbuddy

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class MemoryStore(context:Context) {
    data class Entry(val id:Long,val time:Long,val text:String)
    private class Db(ctx:Context):SQLiteOpenHelper(ctx,"companion_memory.db",null,1) {
        override fun onCreate(db:SQLiteDatabase){db.execSQL("CREATE TABLE memory (id INTEGER PRIMARY KEY, time INTEGER NOT NULL, text TEXT NOT NULL)");db.execSQL("CREATE INDEX memory_time ON memory(time)")}
        override fun onUpgrade(db:SQLiteDatabase,old:Int,new:Int){}
    }
    private val db=getDb(context)
    fun prune(now:Long=System.currentTimeMillis()){db.delete("memory","time < ?",arrayOf((now-DAY).toString()))}
    @Synchronized fun append(text:String,now:Long=System.currentTimeMillis()) {
        val clean=text.trim();if(clean.isBlank())return
        prune(now)
        val recent=entries(1).firstOrNull()
        if(recent?.text==clean && now-recent.time<12000)return
        val v=android.content.ContentValues().apply{put("time",now);put("text",clean)}
        db.insert("memory",null,v)
    }
    fun entries(limit:Int=300):List<Entry> {
        val out=ArrayList<Entry>()
        db.query("memory",arrayOf("id","time","text"),"time >= ?",arrayOf((System.currentTimeMillis()-DAY).toString()),null,null,"time DESC",limit.coerceIn(1,10000).toString()).use { c ->while(c.moveToNext())out+=Entry(c.getLong(0),c.getLong(1),c.getString(2)) }
        return out
    }
    fun clear(){db.delete("memory",null,null)}
    fun deleteIds(ids:List<Long>){ids.forEach{db.delete("memory","id=?",arrayOf(it.toString()))}}
    fun relevant(question:String):String {
        if(!Regex("刚才|之前|今天|昨天|昨晚|早上|晚上|上午|下午|说过|说了|提到|记得|刚刚|回忆|聊了|记忆|听记").containsMatchIn(question))return ""
        val grams=question.windowed(2).filter{it.all{ch->ch in '\u4e00'..'\u9fff'} && it !in setOf("今天","之前","刚才","什么","记得","我说","说过","了吗","了什")}.distinct()
        val all=entries(10000)
        val ranked=all.sortedByDescending{e->grams.count{e.text.contains(it)}}.take(16).sortedBy{it.time}
        val f=java.text.SimpleDateFormat("HH:mm",java.util.Locale.CHINA)
        return ranked.joinToString("\n"){"${f.format(java.util.Date(it.time))} ${it.text.take(250)}"}.take(5000)
    }
    companion object {
        const val DAY=24L*60*60*1000
        @Volatile private var helper:Db?=null
        @Synchronized private fun getDb(ctx:Context):SQLiteDatabase {
            if(helper==null)helper=Db(ctx.applicationContext)
            return helper!!.writableDatabase
        }
    }
}

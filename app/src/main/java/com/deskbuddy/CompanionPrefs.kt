package com.deskbuddy
import android.content.Context
object CompanionPrefs {
    private fun p(c:Context)=c.getSharedPreferences("companion_mode",Context.MODE_PRIVATE)
    fun allDay(c:Context)=p(c).getBoolean("all_day",false)
    fun setAllDay(c:Context,on:Boolean){p(c).edit().putBoolean("all_day",on).apply()}
    fun recallEnabled(c:Context)=p(c).getBoolean("recall",false)
    fun setRecall(c:Context,on:Boolean){p(c).edit().putBoolean("recall",on).apply()}
}

package com.deskbuddy

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.util.TimeZone

/**
 * 宠物能替用户做的手机操作：写入日历、设置闹钟。
 * 这些操作都由语义判断触发，拿不准时先向用户确认，不会静默执行。
 */
object Actions {

    fun hasCalendarPermission(ctx: Context): Boolean =
        granted(ctx, Manifest.permission.READ_CALENDAR) && granted(ctx, Manifest.permission.WRITE_CALENDAR)

    /** 返回 null 表示成功；否则是给用户听的失败原因。 */
    fun addCalendarEvent(ctx: Context, title: String, startMs: Long, minutes: Int): String? {
        if (!hasCalendarPermission(ctx)) return "还没有日历权限，去主页授权一下"
        val calId = writableCalendarId(ctx) ?: return "手机上没有可以写入的日历账户"
        return try {
            val cr = ctx.contentResolver
            val event = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, startMs)
                put(CalendarContract.Events.DTEND, startMs + minutes * 60_000L)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                put(CalendarContract.Events.HAS_ALARM, 1)
            }
            val uri = cr.insert(CalendarContract.Events.CONTENT_URI, event) ?: return "写入日历失败"
            val eventId = uri.lastPathSegment?.toLongOrNull()
            if (eventId != null) {
                // 提前 10 分钟提醒
                val reminder = ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, eventId)
                    put(CalendarContract.Reminders.MINUTES, 10)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                }
                cr.insert(CalendarContract.Reminders.CONTENT_URI, reminder)
            }
            null
        } catch (e: SecurityException) {
            "没有日历权限"
        } catch (e: Exception) {
            "写入日历失败"
        }
    }

    /** 优先用主日历；只选有写入权限的。 */
    private fun writableCalendarId(ctx: Context): Long? {
        val cursor = ctx.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.VISIBLE} = 1",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            "${CalendarContract.Calendars.IS_PRIMARY} DESC"
        ) ?: return null
        return cursor.use { if (it.moveToFirst()) it.getLong(0) else null }
    }

    /** 交给系统时钟应用设置闹钟，不弹界面。返回是否成功发出。 */
    fun setAlarm(ctx: Context, hour: Int, minute: Int, label: String): Boolean {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "小伙伴提醒" })
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(ctx.packageManager) == null) return false
        return try {
            ctx.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun granted(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
}

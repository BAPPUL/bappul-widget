package kr.kro.bappul.planner

import android.content.Context
import java.io.File

/** 마지막으로 받은 Google 일정을 앱 내부 저장소에 보관 (오프라인 표시용). 다른 앱은 읽을 수 없음. */
object CacheStore {
    private const val FILE = "google_calendar_cache.json"
    private val lock = Any()

    private fun file(context: Context) = File(context.filesDir, FILE)

    fun load(context: Context): CalendarCache? = synchronized(lock) {
        val f = file(context)
        if (!f.exists()) null else GoogleCalendarJson.cacheFromJson(f.readText(Charsets.UTF_8))
    }

    /** 저장 직전에 계정이 바뀌었으면 저장하지 않음 (이전 계정 일정이 남지 않게) */
    fun saveIfAccount(context: Context, cache: CalendarCache): Boolean = synchronized(lock) {
        if (Prefs.account(context) != cache.account) return false
        val tmp = File(context.filesDir, "$FILE.tmp")
        tmp.writeText(GoogleCalendarJson.cacheToJson(cache), Charsets.UTF_8)
        tmp.renameTo(file(context))
    }

    fun clear(context: Context) = synchronized(lock) {
        file(context).delete()
        Unit
    }
}

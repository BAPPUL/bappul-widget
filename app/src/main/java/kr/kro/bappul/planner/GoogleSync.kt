package kr.kro.bappul.planner

import android.content.Context
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.concurrent.thread

/** Google 캘린더 → 캐시 → 위젯. 네트워크를 쓰므로 백그라운드 스레드(WorkManager 등)에서 호출. */
object GoogleSync {
    private val lock = Any()

    /** 위젯 화면용 오늘 데이터 (네트워크 없이 캐시만 읽음) */
    fun snapshot(context: Context, now: ZonedDateTime = ZonedDateTime.now()): TodaySnapshot {
        val account = Prefs.account(context)
        return DayBuilder.build(
            account = account,
            cache = CacheStore.load(context),
            selection = account?.let { Prefs.selectedCalendars(context, it) },
            status = Prefs.syncStatus(context),
            now = now,
        )
    }

    /**
     * 일정을 새로 받아서 저장하고 위젯을 다시 그림. 실패하면 오류 종류를 기록 (오프라인이면 마지막 일정 유지).
     * @param freshToken 방금 연결 화면에서 받은 토큰 (없으면 화면 없이 새로 받음)
     * @return 실패했으면 오류 종류, 성공이면 null
     */
    fun sync(context: Context, freshToken: String? = null): SyncError? = synchronized(lock) {
        val account = Prefs.account(context) ?: run { WidgetUpdater.updateAll(context); return null }
        val error = fetch(context, account, freshToken, retry = true)
        if (Prefs.account(context) == account) Prefs.setSyncStatus(context, SyncStatus(error, System.currentTimeMillis()))
        WidgetUpdater.updateAll(context)
        error
    }

    private fun fetch(context: Context, account: String, freshToken: String?, retry: Boolean): SyncError? {
        val token = freshToken ?: when (val t = GoogleAuth.silentToken(context, account)) {
            is GoogleAuth.TokenResult.Ok -> t.token
            is GoogleAuth.TokenResult.NeedsUser -> return if (t.scopeMissing) SyncError.SCOPE else SyncError.AUTH
            is GoogleAuth.TokenResult.Failed -> return if (t.network) SyncError.NETWORK else SyncError.OTHER
        }
        return try {
            val zone = ZoneId.systemDefault()
            val (from, until) = DayBuilder.fetchRange(LocalDate.now(zone))
            val (calendars, events) = GoogleApi.fetchAll(token, from, until, zone)
            CacheStore.saveIfAccount(context, CalendarCache(account, System.currentTimeMillis(), from.toString(), until.toString(), calendars, events))
            null
        } catch (e: HttpStatusException) {
            when {
                e.code == 401 && retry -> { GoogleAuth.clearToken(context, token); fetch(context, account, null, retry = false) }
                e.code == 401 -> SyncError.AUTH
                e.code == 403 && e.body.contains("insufficient", ignoreCase = true) -> SyncError.SCOPE
                else -> SyncError.OTHER
            }
        } catch (e: IOException) {
            SyncError.NETWORK
        } catch (e: Exception) {
            SyncError.OTHER
        }
    }

    /** 연결 화면에서 계정을 정함. 다른 계정이면 이전 계정 일정을 바로 지움. */
    fun onConnected(context: Context, email: String) = synchronized(lock) {
        if (Prefs.account(context) != email) CacheStore.clear(context)
        Prefs.setAccount(context, email)
        Prefs.setSyncStatus(context, SyncStatus(null, 0L))
    }

    /** 연결 끊기: 저장된 일정·상태를 지우고 위젯을 "연결 필요"로. 토큰 철회는 뒤에서 시도. */
    fun disconnect(context: Context) {
        val account = synchronized(lock) {
            val a = Prefs.account(context)
            Prefs.setAccount(context, null)
            CacheStore.clear(context)
            Prefs.setSyncStatus(context, SyncStatus(null, 0L))
            a
        }
        WidgetUpdater.updateAll(context)
        if (account != null) thread(name = "planner-revoke") {
            val t = GoogleAuth.silentToken(context.applicationContext, account)
            if (t is GoogleAuth.TokenResult.Ok) {
                try { GoogleApi.revoke(t.token) } catch (_: Exception) { }
                GoogleAuth.clearToken(context.applicationContext, t.token)
            }
        }
    }
}

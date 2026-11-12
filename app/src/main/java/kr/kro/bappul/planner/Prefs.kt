package kr.kro.bappul.planner

import android.content.Context

/**
 * 앱 설정 저장.
 * - 연결한 Google 계정 (이메일)
 * - 위젯 전용 캘린더 선택: 계정별로 따로 저장. Google 캘린더 앱의 표시/숨김과 상관없음.
 * - 마지막 동기화 결과
 */
object Prefs {
    private const val FILE = "planner"
    private const val KEY_ACCOUNT = "google_account"
    private const val KEY_ERROR = "sync_error"
    private const val KEY_ATTEMPT = "sync_attempt_at"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun selKey(account: String) = "widget_calendars:$account"
    private fun customKey(account: String) = "widget_calendars_custom:$account"

    fun account(context: Context): String? = prefs(context).getString(KEY_ACCOUNT, null)

    fun setAccount(context: Context, email: String?) {
        prefs(context).edit().apply { if (email == null) remove(KEY_ACCOUNT) else putString(KEY_ACCOUNT, email) }.apply()
    }

    /** null 이면 아직 고르지 않음 → Google 에서 보이는 캘린더 전부 (DayBuilder.defaultSelection) */
    fun selectedCalendars(context: Context, account: String): Set<String>? {
        val p = prefs(context)
        if (!p.getBoolean(customKey(account), false)) return null
        return p.getStringSet(selKey(account), emptySet())!!.toSet()
    }

    fun setSelectedCalendars(context: Context, account: String, ids: Set<String>) {
        prefs(context).edit()
            .putBoolean(customKey(account), true)
            .putStringSet(selKey(account), ids)
            .apply()
    }

    fun resetSelection(context: Context, account: String) {
        prefs(context).edit().remove(customKey(account)).remove(selKey(account)).apply()
    }

    fun syncStatus(context: Context): SyncStatus {
        val p = prefs(context)
        val error = p.getString(KEY_ERROR, null)?.let { name -> SyncError.values().firstOrNull { it.name == name } }
        return SyncStatus(error, p.getLong(KEY_ATTEMPT, 0L))
    }

    fun setSyncStatus(context: Context, status: SyncStatus) {
        prefs(context).edit().apply {
            if (status.lastError == null) remove(KEY_ERROR) else putString(KEY_ERROR, status.lastError.name)
            putLong(KEY_ATTEMPT, status.lastAttemptAt)
        }.apply()
    }
}

package kr.kro.bappul.planner

import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * 저장된 Google 일정(캐시) + 위젯 전용 캘린더 선택 + 마지막 갱신 상태 → 오늘 위젯 화면 데이터.
 * Android 의존이 없어 JVM 테스트로 확인함 (DayBuilderTest).
 */
object DayBuilder {

    /** 오래된 데이터로 볼 기준. 이보다 오래되면 위젯에 "HH:mm 기준"을 표시 */
    const val STALE_AFTER_MS = 45L * 60 * 1000

    /**
     * 위젯 선택을 아직 저장하지 않았을 때 기본으로 켤 캘린더:
     * Google 캘린더에서 보이게 해둔(목록에서 숨기지 않고 체크된) 캘린더. 숨긴 캘린더는 기본으로 꺼져 있음.
     */
    fun defaultSelection(calendars: List<GoogleCalendarInfo>): Set<String> =
        calendars.filter { !it.hidden && it.selectedInGoogle }.map { it.id }.toSet()

    /** 이번에 받아올 기간: 오늘과 내일 (자정이 지나도 오프라인으로 하루 더 보여줄 수 있게) */
    fun fetchRange(today: LocalDate): Pair<LocalDate, LocalDate> = today to today.plusDays(2)

    fun build(
        account: String?,
        cache: CalendarCache?,
        selection: Set<String>?,
        status: SyncStatus,
        now: ZonedDateTime,
    ): TodaySnapshot {
        val nowMin = now.hour * 60 + now.minute + now.second / 60f
        if (account == null) return TodaySnapshot(WidgetState.NEEDS_CONNECT, emptyList(), emptyList(), nowMin)

        val alert = when (status.lastError) {
            SyncError.AUTH -> Alert.AUTH_EXPIRED
            SyncError.SCOPE -> Alert.SCOPE_MISSING
            else -> null
        }
        val offline = status.lastError == SyncError.NETWORK
        // 다른 계정의 캐시는 절대 쓰지 않음
        val data = cache?.takeIf { it.account == account }
            ?: return TodaySnapshot(WidgetState.NO_DATA, emptyList(), emptyList(), nowMin, alert, null, offline)

        val known = data.calendars.map { it.id }.toSet()
        val chosen = (selection ?: defaultSelection(data.calendars)) intersect known
        val stale = if (offline || now.toInstant().toEpochMilli() - data.fetchedAt > STALE_AFTER_MS) data.fetchedAt else null
        if (chosen.isEmpty()) return TodaySnapshot(WidgetState.NO_CALENDAR, emptyList(), emptyList(), nowMin, alert, stale, offline)

        val today = now.toLocalDate()
        val todayKey = today.toString()
        if (todayKey < data.rangeStart || todayKey >= data.rangeEnd) {
            // 받아둔 기간을 벗어남 (오래 오프라인)
            return TodaySnapshot(WidgetState.NO_DATA, emptyList(), emptyList(), nowMin, alert, data.fetchedAt, offline)
        }
        val dayStart = today.atStartOfDay(now.zone).toInstant().toEpochMilli()
        val dayEnd = today.plusDays(1).atStartOfDay(now.zone).toInstant().toEpochMilli()

        val timed = mutableListOf<DayEvent>()
        val allDay = mutableListOf<DayEvent>()
        for (e in data.events) {
            if (e.calendarId !in chosen) continue
            if (e.allDay) {
                val s = e.startDate ?: continue
                val end = e.endDate ?: s
                if (s <= todayKey && (todayKey < end || s == end && s == todayKey)) {
                    allDay += DayEvent(e.title, 0, 1440, e.color, true, e.calendarId)
                }
                continue
            }
            if (e.endMs <= dayStart || e.startMs >= dayEnd) continue
            val s = if (e.startMs <= dayStart) 0 else ((e.startMs - dayStart) / 60_000L).toInt()
            val en = if (e.endMs >= dayEnd) 1440 else ((e.endMs - dayStart) / 60_000L).toInt()
            if (en > s) timed += DayEvent(e.title, s, en, e.color, false, e.calendarId)
        }
        timed.sortWith(compareBy({ it.startMin }, { -it.endMin }))
        val state = if (timed.isEmpty() && allDay.isEmpty()) WidgetState.EMPTY else WidgetState.NORMAL
        return TodaySnapshot(state, timed, allDay, nowMin, alert, stale, offline)
    }
}

package kr.kro.bappul.planner

/** 오늘 하루 안의 일정 하나. 시간은 자정부터의 분(0~1440). */
data class DayEvent(
    val title: String,
    val startMin: Int,
    val endMin: Int,
    val color: Int,
    val allDay: Boolean,
    val calendarId: String,
)

/** Google 캘린더 목록의 캘린더 하나 (calendarList, showHidden=true 로 숨긴 캘린더도 포함). */
data class GoogleCalendarInfo(
    val id: String,
    val name: String,
    val color: Int,
    /** Google 캘린더에서 "목록에서 숨김" */
    val hidden: Boolean,
    /** Google 캘린더 목록에서 체크(표시)돼 있는지 */
    val selectedInGoogle: Boolean,
    val primary: Boolean,
)

/** Google Calendar API 에서 받은 일정 하나 (반복 일정은 서버가 회차별로 펼쳐서 줌). */
data class CachedEvent(
    val calendarId: String,
    val title: String,
    val allDay: Boolean,
    /** 시간 일정: 시작·끝 (epoch ms). 종일 일정은 0 */
    val startMs: Long,
    val endMs: Long,
    /** 종일 일정: yyyy-MM-dd (끝 날짜는 포함 안 함). 시간 일정은 null */
    val startDate: String?,
    val endDate: String?,
    val color: Int,
)

/** 마지막으로 받아온 일정 묶음. 계정이 다르면 쓰지 않음. */
data class CalendarCache(
    val account: String,
    val fetchedAt: Long,
    /** 받아온 기간 yyyy-MM-dd ~ (끝 날짜 미포함) */
    val rangeStart: String,
    val rangeEnd: String,
    val calendars: List<GoogleCalendarInfo>,
    val events: List<CachedEvent>,
)

enum class SyncError {
    /** 인터넷 연결 문제 */
    NETWORK,
    /** 로그인 만료·권한 철회 → 앱에서 다시 연결 필요 */
    AUTH,
    /** 캘린더 읽기 권한을 일부만 허용함 */
    SCOPE,
    /** 그 밖의 오류 (API 꺼짐 등) */
    OTHER,
}

data class SyncStatus(val lastError: SyncError?, val lastAttemptAt: Long)

enum class WidgetState {
    /** 일정이 하나 이상 있음 */
    NORMAL,
    /** 오늘 일정이 없음 */
    EMPTY,
    /** Google 계정을 아직 연결하지 않음 */
    NEEDS_CONNECT,
    /** 표시할 캘린더를 하나도 고르지 않음 */
    NO_CALENDAR,
    /** 연결은 됐지만 오늘 일정을 아직 받아오지 못함 */
    NO_DATA,
}

/** 위젯에서 눈에 띄게 알려야 하는 문제. 있으면 누를 때 앱이 열림. */
enum class Alert { AUTH_EXPIRED, SCOPE_MISSING }

/** 위젯 한 번 그리는 데 필요한 오늘 데이터. */
data class TodaySnapshot(
    val state: WidgetState,
    /** 시간이 정해진 일정 (시작 순) */
    val events: List<DayEvent>,
    /** 종일 일정 */
    val allDay: List<DayEvent>,
    /** 스냅샷을 만든 시각 (자정부터의 분) */
    val nowMin: Float,
    val alert: Alert? = null,
    /** 오래된(또는 오프라인) 데이터면 그 데이터를 받아온 시각 */
    val staleSince: Long? = null,
    /** 마지막 갱신이 인터넷 문제로 실패함 */
    val offline: Boolean = false,
) {
    /** 지금 이후에 시작하는 첫 일정 */
    val next: DayEvent? get() = events.filter { it.startMin > nowMin }.minByOrNull { it.startMin }

    /** 누르면 사이트 대신 앱(설정·다시 연결)을 열어야 하는지 */
    val needsApp: Boolean
        get() = alert != null || state == WidgetState.NEEDS_CONNECT ||
            state == WidgetState.NO_CALENDAR || state == WidgetState.NO_DATA
}

/** 기존 사이트(bappul.kro.kr)와 같은 색. */
object Palette {
    const val BG = 0xFF0D1020.toInt()
    const val PANEL = 0xFF151A2D.toInt()
    const val PANEL_2 = 0xFF1C2239.toInt()
    const val HOLE = 0xFF111527.toInt()
    const val TRACK = 0xFF1B2038.toInt()
    const val TRACK_PAST = 0xFF232A48.toInt()
    const val TICK = 0xFF4A5175.toInt()
    const val INK = 0xFFEEF0FB.toInt()
    const val MUTED = 0xFF9EA5C3.toInt()
    const val FAINT = 0xFF676E8F.toInt()
    const val ACCENT = 0xFFA3AFFF.toInt()
    const val CORAL = 0xFFFF6F61.toInt()
    const val DARK_TEXT = 0xFF151829.toInt()

    /** 색 정보가 없을 때 돌려 쓰는 색 (사이트 예시 일정과 같음) */
    val FALLBACK = intArrayOf(
        0xFFC8BEF2.toInt(), 0xFFFFD2A5.toInt(), 0xFFACDCCA.toInt(),
        0xFFF0B8D4.toInt(), 0xFFBDD8F6.toInt(), 0xFFEEE0A1.toInt(),
    )

    /** Google 캘린더 일정 색(colorId 1~11) */
    val GOOGLE_EVENT = mapOf(
        "1" to 0xFF7986CB.toInt(), "2" to 0xFF33B679.toInt(), "3" to 0xFF8E24AA.toInt(),
        "4" to 0xFFE67C73.toInt(), "5" to 0xFFF6BF26.toInt(), "6" to 0xFFF4511E.toInt(),
        "7" to 0xFF039BE5.toInt(), "8" to 0xFF616161.toInt(), "9" to 0xFF3F51B5.toInt(),
        "10" to 0xFF0B8043.toInt(), "11" to 0xFFD50000.toInt(),
    )

    fun parseHex(hex: String?, fallback: Int): Int {
        val h = hex?.removePrefix("#") ?: return fallback
        return if (h.length == 6) (h.toLongOrNull(16)?.toInt()?.or(0xFF000000.toInt()) ?: fallback) else fallback
    }

    fun toHex(color: Int): String = "#%06X".format(color and 0xFFFFFF)
}

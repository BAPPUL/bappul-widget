package kr.kro.bappul.planner

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/** 위젯 화면(RemoteViews)을 만들고 홈 화면에 반영. 데이터는 저장된 Google 일정(캐시)만 읽음 — 네트워크 없음. */
object WidgetUpdater {

    const val SITE_URL = "https://www.bappul.kro.kr/"

    /** 4×4 기본 크기 (위젯 옵션을 못 받을 때) */
    private const val DEFAULT_W_DP = 320
    private const val DEFAULT_H_DP = 350
    /** 레이아웃 widget_planner.xml 과 맞춘 값 */
    private const val PADDING_DP = 14
    private const val BOTTOM_ROW_DP = 46 + 10
    /** 2×2 기본 크기와 여백 (widget_planner_small.xml) */
    private const val SMALL_DEFAULT_W_DP = 170
    private const val SMALL_DEFAULT_H_DP = 180
    private const val SMALL_PADDING_DP = 8

    /** 홈 화면에 놓인 위젯 전부 (4×4 + 2×2) */
    fun widgetIds(context: Context): IntArray {
        val manager = AppWidgetManager.getInstance(context)
        return manager.getAppWidgetIds(ComponentName(context, PlannerWidgetProvider::class.java)) +
            manager.getAppWidgetIds(ComponentName(context, PlannerWidgetSmallProvider::class.java))
    }

    private fun isSmall(manager: AppWidgetManager, id: Int): Boolean =
        manager.getAppWidgetInfo(id)?.provider?.className == PlannerWidgetSmallProvider::class.java.name

    /** 홈 화면에 놓인 위젯 전부 다시 그림 + 다음 5분 갱신 예약 */
    fun updateAll(context: Context) {
        val ids = widgetIds(context)
        if (ids.isNotEmpty()) update(context, ids)
        RefreshScheduler.scheduleTick(context, ids.isNotEmpty())
    }

    fun update(context: Context, ids: IntArray) {
        val manager = AppWidgetManager.getInstance(context)
        val snapshot = GoogleSync.snapshot(context)
        for (id in ids) {
            val options = manager.getAppWidgetOptions(id)
            val views = if (isSmall(manager, id)) buildSmallViews(context, snapshot, options)
                        else buildViews(context, snapshot, options)
            manager.updateAppWidget(id, views)
        }
    }

    /** 4×4 위젯 화면. 앱 설정 화면의 미리보기에도 같은 함수를 씀. */
    fun buildViews(context: Context, snap: TodaySnapshot, options: Bundle?): RemoteViews {
        val density = context.resources.displayMetrics.density
        // 세로 화면 기준: 너비는 최소값, 높이는 최대값이 실제 크기에 가까움
        val wDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)?.takeIf { it > 0 } ?: DEFAULT_W_DP
        val hDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)?.takeIf { it > 0 } ?: DEFAULT_H_DP
        val ringDp = min(wDp - PADDING_DP * 2, hDp - PADDING_DP * 2 - BOTTOM_ROW_DP).coerceIn(150, 440)
        // 비트맵이 너무 크면 위젯 전송 한도를 넘을 수 있어 최대 크기를 제한
        val ringPx = min((ringDp * density).roundToInt(), 960)
        val renderDensity = ringPx / ringDp.toFloat()

        val views = RemoteViews(context.packageName, R.layout.widget_planner)
        views.setImageViewBitmap(R.id.ring, WheelRenderer.render(ringPx, renderDensity, snap))

        // 가운데 시계: 시스템 시계(TextClock)가 직접 표시하므로 위젯을 매분 갱신할 필요 없음
        val hole = WheelRenderer.Geometry(ringPx.toFloat(), renderDensity).holeDiameterDp
        views.setTextViewTextSize(R.id.clock, TypedValue.COMPLEX_UNIT_DIP, (hole * 0.29f).coerceIn(30f, 64f))
        views.setTextViewTextSize(R.id.date, TypedValue.COMPLEX_UNIT_DIP, (hole * 0.082f).coerceIn(11f, 15f))
        views.setTextViewTextSize(R.id.all_day, TypedValue.COMPLEX_UNIT_DIP, (hole * 0.07f).coerceIn(10f, 13f))
        views.setTextViewTextSize(R.id.stale, TypedValue.COMPLEX_UNIT_DIP, (hole * 0.066f).coerceIn(10f, 12f))

        // 종일 일정 (가운데 날짜 아래 한 줄)
        if (snap.allDay.isNotEmpty() && snap.state == WidgetState.NORMAL) {
            val extra = if (snap.allDay.size > 1) " 외 ${snap.allDay.size - 1}" else ""
            views.setTextViewText(R.id.all_day, "종일 · ${snap.allDay.first().title}$extra")
            views.setViewVisibility(R.id.all_day, View.VISIBLE)
        } else views.setViewVisibility(R.id.all_day, View.GONE)
        showStale(context, views, snap)

        // 아래 한 줄: 다음 일정 이름 + 시작 시간 (문제가 있으면 안내 문구)
        val next = snap.next
        when {
            snap.alert == Alert.AUTH_EXPIRED -> bottomMessage(views, "Google 연결이 만료됐어요", "다시 연결", Palette.CORAL)
            snap.alert == Alert.SCOPE_MISSING -> bottomMessage(views, "캘린더 읽기 권한이 필요해요", "허용하기", Palette.CORAL)
            snap.state == WidgetState.NEEDS_CONNECT -> bottomMessage(views, "Google 계정을 연결해 주세요", "연결", Palette.ACCENT)
            snap.state == WidgetState.NO_CALENDAR -> bottomMessage(views, "표시할 캘린더를 골라 주세요", "설정", Palette.ACCENT)
            snap.state == WidgetState.NO_DATA -> bottomMessage(views,
                if (snap.offline) "인터넷에 연결되면 일정을 불러와요" else "일정을 불러오는 중…", "", Palette.MUTED)
            next != null -> {
                views.setViewVisibility(R.id.next_dot, View.VISIBLE)
                views.setInt(R.id.next_dot, "setColorFilter", next.color)
                views.setViewVisibility(R.id.next_label, View.VISIBLE)
                views.setTextViewText(R.id.next_title, next.title)
                views.setTextColor(R.id.next_title, Palette.INK)
                views.setTextViewText(R.id.next_time, formatTime(context, next.startMin))
                views.setTextColor(R.id.next_time, Palette.INK)
            }
            else -> bottomMessage(views,
                if (snap.state == WidgetState.EMPTY) "오늘은 일정이 없어요" else "오늘 남은 일정이 없어요", "", Palette.MUTED)
        }

        views.setOnClickPendingIntent(R.id.widget_root, tapIntent(context, snap))
        views.setContentDescription(R.id.widget_root, describe(context, snap))
        return views
    }

    /**
     * 2×2 위젯: 원형 시간표를 꽉 채우고, 가운데에 시각 + 다음 일정(시작 시간 / 이름 두 줄).
     * 바깥 숫자와 조각 제목은 생략.
     */
    fun buildSmallViews(context: Context, snap: TodaySnapshot, options: Bundle?): RemoteViews {
        val density = context.resources.displayMetrics.density
        val wDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)?.takeIf { it > 0 } ?: SMALL_DEFAULT_W_DP
        val hDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)?.takeIf { it > 0 } ?: SMALL_DEFAULT_H_DP
        val ringDp = min(wDp - SMALL_PADDING_DP * 2, hDp - SMALL_PADDING_DP * 2).coerceIn(100, 320)
        val ringPx = min((ringDp * density).roundToInt(), 720)
        val renderDensity = ringPx / ringDp.toFloat()

        val views = RemoteViews(context.packageName, R.layout.widget_planner_small)
        views.setImageViewBitmap(R.id.ring, WheelRenderer.render(ringPx, renderDensity, snap, compact = true))
        val hole = WheelRenderer.Geometry(ringPx.toFloat(), renderDensity, compact = true).holeDiameterDp
        views.setTextViewTextSize(R.id.clock, TypedValue.COMPLEX_UNIT_DIP, (hole * 0.27f).coerceIn(20f, 44f))
        val small = (hole * 0.115f).coerceIn(10f, 14f)
        views.setTextViewTextSize(R.id.next_time, TypedValue.COMPLEX_UNIT_DIP, small)
        views.setTextViewTextSize(R.id.next_title, TypedValue.COMPLEX_UNIT_DIP, small + 2f)
        views.setTextViewTextSize(R.id.stale, TypedValue.COMPLEX_UNIT_DIP, (small - 1f).coerceAtLeast(9f))
        views.setInt(R.id.next_title, "setMaxWidth", (hole * 0.78f * density).roundToInt())
        showStale(context, views, snap, short = true)

        fun lines(top: String, topColor: Int, bottom: String, bottomColor: Int, dotColor: Int?) {
            if (dotColor != null) {
                views.setViewVisibility(R.id.next_dot, View.VISIBLE)
                views.setInt(R.id.next_dot, "setColorFilter", dotColor)
            } else views.setViewVisibility(R.id.next_dot, View.GONE)
            views.setViewVisibility(R.id.next_meta, if (top.isEmpty()) View.GONE else View.VISIBLE)
            views.setTextViewText(R.id.next_time, top)
            views.setTextColor(R.id.next_time, topColor)
            views.setTextViewText(R.id.next_title, bottom)
            views.setTextColor(R.id.next_title, bottomColor)
        }
        val next = snap.next
        when {
            snap.alert == Alert.AUTH_EXPIRED -> lines("연결 만료", Palette.CORAL, "다시 연결", Palette.INK, null)
            snap.alert == Alert.SCOPE_MISSING -> lines("권한 필요", Palette.CORAL, "눌러서 허용", Palette.MUTED, null)
            snap.state == WidgetState.NEEDS_CONNECT -> lines("Google 연결", Palette.ACCENT, "눌러서 연결", Palette.MUTED, null)
            snap.state == WidgetState.NO_CALENDAR -> lines("캘린더 선택", Palette.ACCENT, "눌러서 설정", Palette.MUTED, null)
            snap.state == WidgetState.NO_DATA -> lines("", Palette.MUTED, if (snap.offline) "오프라인" else "불러오는 중…", Palette.MUTED, null)
            next != null -> lines("다음 · " + formatTime(context, next.startMin), Palette.MUTED, next.title, Palette.INK, next.color)
            else -> lines("", Palette.MUTED, if (snap.state == WidgetState.EMPTY) "일정 없음" else "남은 일정 없음", Palette.MUTED, null)
        }
        views.setOnClickPendingIntent(R.id.widget_root, tapIntent(context, snap))
        views.setContentDescription(R.id.widget_root, describe(context, snap))
        return views
    }

    /** 오래된 데이터·오프라인이면 "21:10 기준" 표시 */
    private fun showStale(context: Context, views: RemoteViews, snap: TodaySnapshot, short: Boolean = false) {
        val text = staleText(context, snap, short)
        if (text == null) views.setViewVisibility(R.id.stale, View.GONE)
        else {
            views.setViewVisibility(R.id.stale, View.VISIBLE)
            views.setTextViewText(R.id.stale, text)
            views.setTextColor(R.id.stale, if (snap.offline) Palette.MUTED else Palette.FAINT)
        }
    }

    fun staleText(context: Context, snap: TodaySnapshot, short: Boolean = false): String? {
        val at = snap.staleSince ?: return null
        val then = Calendar.getInstance().apply { timeInMillis = at }
        val now = Calendar.getInstance()
        val sameDay = then.get(Calendar.YEAR) == now.get(Calendar.YEAR) && then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
        val time = formatTime(context, then.get(Calendar.HOUR_OF_DAY) * 60 + then.get(Calendar.MINUTE))
        val day = if (sameDay) "" else SimpleDateFormat("M/d ", Locale.getDefault()).format(Date(at))
        val prefix = if (snap.offline && !short) "오프라인 · " else ""
        return "$prefix$day$time 기준"
    }

    private fun bottomMessage(views: RemoteViews, title: String, action: String, actionColor: Int) {
        views.setViewVisibility(R.id.next_dot, View.GONE)
        views.setViewVisibility(R.id.next_label, View.GONE)
        views.setTextViewText(R.id.next_title, title)
        views.setTextColor(R.id.next_title, if (actionColor == Palette.MUTED) Palette.MUTED else Palette.INK)
        views.setTextViewText(R.id.next_time, action)
        views.setTextColor(R.id.next_time, actionColor)
    }

    /** 누르면: 연결·권한·캘린더 문제가 있으면 앱(다시 연결), 아니면 생활계획표 사이트 */
    private fun tapIntent(context: Context, snap: TodaySnapshot): PendingIntent {
        val intent = if (snap.needsApp)
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        else
            Intent(Intent.ACTION_VIEW, Uri.parse(SITE_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context, if (snap.needsApp) 1 else 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** 휴대폰 설정의 24시간제 여부를 따름: 15:00 / 오후 3:00 */
    fun formatTime(context: Context, minuteOfDay: Int): String {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, minuteOfDay / 60 % 24); set(Calendar.MINUTE, minuteOfDay % 60)
        }
        val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "a h:mm"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(cal.time)
    }

    private fun describe(context: Context, snap: TodaySnapshot): String {
        val base = "오늘의 생활계획표."
        val problem = when {
            snap.alert == Alert.AUTH_EXPIRED -> " Google 연결이 만료됐어요. 누르면 다시 연결할 수 있어요."
            snap.alert == Alert.SCOPE_MISSING -> " 캘린더 읽기 권한이 필요해요. 누르면 앱이 열려요."
            snap.state == WidgetState.NEEDS_CONNECT -> " Google 계정을 연결해 주세요. 누르면 앱이 열려요."
            snap.state == WidgetState.NO_CALENDAR -> " 표시할 캘린더를 골라 주세요."
            snap.state == WidgetState.NO_DATA -> " 일정을 아직 불러오지 못했어요."
            else -> ""
        }
        if (problem.isNotEmpty()) return base + problem
        val next = snap.next
        return base + (if (snap.state == WidgetState.EMPTY) " 오늘은 일정이 없어요." else " 오늘 일정 ${snap.events.size + snap.allDay.size}개.") +
            (next?.let { " 다음 일정 ${it.title}, ${formatTime(context, it.startMin)}." } ?: "") +
            (staleText(context, snap)?.let { " $it." } ?: "") +
            " 누르면 생활계획표 사이트가 열려요."
    }
}

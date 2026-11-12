package kr.kro.bappul.planner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 24시간 원형 시간표를 비트맵으로 그림.
 * 자정이 12시 방향, 시계 방향으로 하루가 한 바퀴. 가운데 구멍에는 위젯 레이아웃의 TextClock 이 올라감.
 * preview/mockup.html 의 drawWheel() 과 같은 규칙으로 그림 (목업과 실제 위젯 모양을 맞추기 위해).
 */
object WheelRenderer {

    /** compact = 2×2 위젯: 바깥 숫자·조각 제목 없이 링을 꽉 채움 */
    class Geometry(val size: Float, val density: Float, val compact: Boolean = false) {
        /** 바깥 시각 숫자(0·6·12·18)가 들어갈 여백 */
        val labelSpace = (if (compact) 7f else 17f) * density
        val outer = size / 2f - labelSpace
        val thickness = outer * (if (compact) 0.30f else 0.34f)
        /** 바깥 눈금 점과 바늘 끝 위치 */
        val dotRadius = outer + (if (compact) 3.5f else 5f) * density
        val inner = outer - thickness
        val holeRadius = inner - 3f * density
        /** 가운데 구멍 지름 (dp) — 시계 글자 크기를 정할 때 씀 */
        val holeDiameterDp get() = holeRadius * 2f / density
    }

    fun render(sizePx: Int, density: Float, snap: TodaySnapshot, compact: Boolean = false): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val g = Geometry(sizePx.toFloat(), density, compact)
        val d = density
        c.translate(g.size / 2f, g.size / 2f)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        val disabled = snap.state == WidgetState.NEEDS_CONNECT || snap.state == WidgetState.NO_CALENDAR || snap.state == WidgetState.NO_DATA

        // 1) 빈 시간 트랙 + 오늘 지난 시간
        fill.color = Palette.TRACK
        c.drawPath(ringPath(g.inner, g.outer), fill)
        if (snap.nowMin > 0f) {
            fill.color = Palette.TRACK_PAST
            c.drawPath(sector(g.inner, g.outer, minuteToDeg(0f), snap.nowMin / 1440f * 360f), fill)
        }
        if (disabled) {
            stroke.color = Palette.FAINT
            stroke.alpha = 150
            stroke.strokeWidth = 1.5f * d
            stroke.pathEffect = DashPathEffect(floatArrayOf(4f * d, 5f * d), 0f)
            c.drawCircle(0f, 0f, (g.inner + g.outer) / 2f, stroke)
            stroke.pathEffect = null
            stroke.alpha = 255
        }

        // 2) 일정 조각 (겹치는 일정끼리만 칸을 나눔)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = (g.size * 0.038f).coerceIn(10f * d, 14f * d)
        }
        for (slice in layoutLanes(snap.events)) {
            val e = slice.event
            val band = g.thickness / slice.lanes
            val o = g.outer - slice.lane * band
            val i = o - band
            val startDeg = minuteToDeg(e.startMin.toFloat())
            val sweep = (e.endMin - e.startMin) / 1440f * 360f
            val inset = (if (compact) 1.2f else 1.6f) * d
            val ga = min(toDeg(inset / i), sweep / 4f)
            val path = sector(i + inset, o - inset, startDeg + ga, sweep - 2 * ga)
            // 지난 일정은 트랙 색과 섞어 차분하게 (반투명 대신 불투명 색이라 테두리가 겹쳐 보이지 않음)
            val faded = e.endMin <= snap.nowMin
            val color = if (faded) blend(e.color, Palette.TRACK_PAST, 0.5f) else e.color
            fill.color = color
            fill.alpha = 255
            stroke.color = color
            stroke.alpha = 255
            stroke.strokeWidth = 2.2f * d
            stroke.strokeJoin = Paint.Join.ROUND
            c.drawPath(path, fill)
            c.drawPath(path, stroke)

            // 제목: ① 가로로 들어가면 가로로 ② 아니면 링을 따라 눕혀서 ③ 그래도 안 되면 생략 (좁은 조각)
            val rad = (i + o) / 2f
            val midDeg = startDeg + sweep / 2f
            val midRad = toRad(midDeg)
            val x = cos(midRad).toFloat() * rad
            val y = sin(midRad).toFloat() * rad
            val w = textPaint.measureText(e.title) + 4f * d
            val h = textPaint.textSize * 1.25f
            val pad = 4f * d
            if (compact) continue   // 2×2 는 조각이 작아 제목을 넣지 않음
            textPaint.color = textOn(color)
            textPaint.alpha = if (faded) 210 else 255
            val fm = textPaint.fontMetrics
            val baseline = -(fm.ascent + fm.descent) / 2f
            if (fitsBox(x, y, w, h, i, o, toRad(startDeg), toRad(sweep), d)) {
                c.drawText(e.title, x, y + baseline, textPaint)
            } else if (band >= h + 2 * pad && (rad - h / 2f) * toRad(sweep) >= w + 2 * pad) {
                c.save()
                c.translate(x, y)
                // 링의 접선 방향. 아래쪽 절반은 뒤집어서 글자가 거꾸로 보이지 않게
                c.rotate(if (sin(midRad) > 0) midDeg - 90f else midDeg + 90f)
                c.drawText(e.title, 0f, baseline, textPaint)
                c.restore()
            }
        }

        // 3) 바깥 눈금: 매시 점, 6시간마다 굵은 점과 숫자
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = 10.5f * d
            color = Palette.MUTED
        }
        for (hour in 0 until 24) {
            val a = toRad(minuteToDeg(hour * 60f))
            val major = hour % 6 == 0
            if (compact && hour % 3 != 0) continue   // 2×2: 3시간마다 점만
            fill.color = if (major) Palette.MUTED else Palette.TICK
            fill.alpha = 255
            val dotR = g.dotRadius
            c.drawCircle(cos(a).toFloat() * dotR, sin(a).toFloat() * dotR, (if (major) 1.8f else 1.0f) * d, fill)
            if (major && !compact) {
                val lr = g.outer + 12.5f * d
                val fm = labelPaint.fontMetrics
                c.drawText(hour.toString(), cos(a).toFloat() * lr, sin(a).toFloat() * lr - (fm.ascent + fm.descent) / 2f, labelPaint)
            }
        }

        // 4) 현재 시각 바늘 (코랄)
        run {
            val a = toRad(minuteToDeg(snap.nowMin))
            val cx = cos(a).toFloat()
            val cy = sin(a).toFloat()
            val tip = g.dotRadius
            stroke.color = Palette.CORAL
            stroke.alpha = 255
            stroke.strokeWidth = 2.6f * d
            stroke.strokeCap = Paint.Cap.ROUND
            c.drawLine(cx * (g.inner - 1f * d), cy * (g.inner - 1f * d), cx * tip, cy * tip, stroke)
            fill.color = Palette.CORAL
            c.drawCircle(cx * tip, cy * tip, (if (compact) 3f else 3.6f) * d, fill)
            fill.color = Palette.BG
            c.drawCircle(cx * tip, cy * tip, 1.4f * d, fill)
        }

        // 5) 가운데 구멍 (여기에 TextClock 이 올라감)
        fill.color = Palette.HOLE
        c.drawCircle(0f, 0f, g.holeRadius, fill)
        return bmp
    }

    // ── 도우미 ──

    data class Slice(val event: DayEvent, val lane: Int, var lanes: Int = 1)

    /** 겹치는 일정 묶음마다 필요한 칸 수를 계산 (사이트와 같은 방식) */
    fun layoutLanes(events: List<DayEvent>): List<Slice> {
        val slices = mutableListOf<Slice>()
        var cluster = mutableListOf<Slice>()
        val laneEnds = mutableListOf<Int>()
        var clusterEnd = -1
        fun closeCluster() {
            val n = max(1, laneEnds.size)
            cluster.forEach { it.lanes = n }
            cluster = mutableListOf()
            laneEnds.clear()
        }
        for (e in events.filter { !it.allDay }.sortedBy { it.startMin }) {
            if (e.startMin >= clusterEnd) closeCluster()
            var lane = laneEnds.indexOfFirst { it <= e.startMin }
            if (lane < 0) { lane = laneEnds.size; laneEnds += e.endMin } else laneEnds[lane] = e.endMin
            clusterEnd = max(clusterEnd, e.endMin)
            val s = Slice(e, lane)
            cluster += s
            slices += s
        }
        closeCluster()
        return slices
    }

    private fun minuteToDeg(m: Float) = m / 1440f * 360f - 90f
    private fun toRad(deg: Float) = deg / 180.0 * PI
    private fun toDeg(rad: Float) = (rad / PI * 180.0).toFloat()

    private fun ringPath(inner: Float, outer: Float) = Path().apply {
        fillType = Path.FillType.EVEN_ODD
        addCircle(0f, 0f, outer, Path.Direction.CW)
        addCircle(0f, 0f, inner, Path.Direction.CW)
    }

    private fun sector(inner: Float, outer: Float, startDeg: Float, sweepDeg: Float) = Path().apply {
        val sweep = max(0.1f, sweepDeg)
        arcTo(RectF(-outer, -outer, outer, outer), startDeg, sweep, true)
        arcTo(RectF(-inner, -inner, inner, inner), startDeg + sweep, -sweep, false)
        close()
    }

    /** 가로 글상자가 부채꼴 조각 안에 완전히 들어가는지 (모서리·변 중간 8점 검사) */
    private fun fitsBox(cx: Float, cy: Float, w: Float, h: Float, inner: Float, outer: Float,
                        startRad: Double, spanRad: Double, d: Float): Boolean {
        val pad = 4f * d
        for (dx in floatArrayOf(-0.5f, 0f, 0.5f)) for (dy in floatArrayOf(-0.5f, 0f, 0.5f)) {
            if (dx == 0f && dy == 0f) continue
            val x = cx + dx * w
            val y = cy + dy * h
            val r = hypot(x, y)
            if (r < inner + pad || r > outer - pad) return false
            var rel = (atan2(y.toDouble(), x.toDouble()) - startRad) % (2 * PI)
            if (rel < 0) rel += 2 * PI
            val angPad = pad / r
            if (rel < angPad || rel > spanRad - angPad) return false
        }
        return true
    }

    private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(),
        (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(),
        (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt(),
    )

    /** 조각 배경색에 맞춰 글자색 선택 */
    fun textOn(color: Int): Int {
        val l = 0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)
        return if (l > 150) Palette.DARK_TEXT else Color.WHITE
    }
}

package kr.kro.bappul.planner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 핵심 시나리오: Google 캘린더에서 숨긴 '고정 일정' 캘린더를 위젯에서만 켜면
 * 그 캘린더의 일정과 반복 일정이 오늘 시간표에 들어가고, 끄면 빠지는지.
 * 실제 Calendar API 응답 모양의 JSON 으로 확인함.
 */
class DayBuilderTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val today = LocalDate.of(2026, 11, 12)
    private val now = ZonedDateTime.of(2026, 11, 12, 14, 20, 0, 0, zone)
    private val me = "bappul@gmail.com"
    private val fixedId = "fixed123@group.calendar.google.com"
    private val ok = SyncStatus(null, 0L)

    private val calendarListJson = """
    {"kind":"calendar#calendarList","items":[
      {"id":"$me","summary":"$me","backgroundColor":"#9fc6e7","selected":true,"primary":true,"accessRole":"owner"},
      {"id":"$fixedId","summary":"고정 일정","backgroundColor":"#b99aff","hidden":true,"accessRole":"owner"},
      {"id":"ko.south_korea#holiday@group.v.calendar.google.com","summary":"대한민국의 휴일","backgroundColor":"#16a765","selected":true,"accessRole":"reader"},
      {"id":"old@group.calendar.google.com","summary":"예전 캘린더","backgroundColor":"#cccccc","accessRole":"owner"}
    ]}"""

    // 반복 일정: singleEvents=true 응답이라 회차별로 옴 (recurringEventId 포함)
    private val fixedEventsJson = """
    {"items":[
      {"id":"run_20261112","recurringEventId":"run","status":"confirmed","summary":"아침 운동",
       "start":{"dateTime":"2026-11-12T06:30:00+09:00"},"end":{"dateTime":"2026-11-12T07:30:00+09:00"}},
      {"id":"up_20261112","recurringEventId":"up","status":"confirmed","summary":"영상 업로드","colorId":"11",
       "start":{"dateTime":"2026-11-12T20:00:00+09:00"},"end":{"dateTime":"2026-11-12T21:00:00+09:00"}},
      {"id":"run_cancel","recurringEventId":"run","status":"cancelled"},
      {"id":"run_20261113","recurringEventId":"run","status":"confirmed","summary":"아침 운동",
       "start":{"dateTime":"2026-11-13T06:30:00+09:00"},"end":{"dateTime":"2026-11-13T07:30:00+09:00"}}
    ]}"""

    private val primaryEventsJson = """
    {"items":[
      {"id":"lunch","status":"confirmed","summary":"점심","start":{"dateTime":"2026-11-12T12:00:00+09:00"},"end":{"dateTime":"2026-11-12T13:00:00+09:00"}},
      {"id":"meet","status":"confirmed","summary":"거절한 회의","start":{"dateTime":"2026-11-12T15:00:00+09:00"},"end":{"dateTime":"2026-11-12T16:00:00+09:00"},
       "attendees":[{"email":"$me","self":true,"responseStatus":"declined"}]},
      {"id":"late","status":"confirmed","summary":"밤샘 편집","start":{"dateTime":"2026-11-12T23:00:00+09:00"},"end":{"dateTime":"2026-11-13T01:00:00+09:00"}},
      {"id":"bday","status":"confirmed","summary":"가족 생일","start":{"date":"2026-11-12"},"end":{"date":"2026-11-13"}},
      {"id":"wl","status":"confirmed","eventType":"workingLocation","summary":"집","start":{"date":"2026-11-12"},"end":{"date":"2026-11-13"}}
    ]}"""

    private fun cache(account: String = me, fetchedAt: Long = now.toInstant().toEpochMilli() - 5 * 60_000): CalendarCache {
        val (calendars, _) = GoogleCalendarJson.parseCalendarList(calendarListJson)
        val fixed = calendars.first { it.id == fixedId }
        val primary = calendars.first { it.id == me }
        val events = GoogleCalendarJson.parseEvents(fixedEventsJson, fixed.id, fixed.color).first +
            GoogleCalendarJson.parseEvents(primaryEventsJson, primary.id, primary.color).first
        val (from, until) = DayBuilder.fetchRange(today)
        return CalendarCache(account, fetchedAt, from.toString(), until.toString(), calendars, events)
    }

    private fun titles(s: TodaySnapshot) = s.events.map { it.title }

    @Test fun hiddenCalendarIsListedButOffByDefault() {
        val c = cache()
        val fixed = c.calendars.first { it.id == fixedId }
        assertTrue("숨긴 캘린더도 목록에 있어야 함", fixed.hidden)
        assertEquals("고정 일정", fixed.name)
        assertFalse(fixedId in DayBuilder.defaultSelection(c.calendars))
        val s = DayBuilder.build(me, c, null, ok, now)
        assertFalse("기본 선택에선 숨긴 캘린더 일정이 빠짐", "아침 운동" in titles(s))
        assertEquals(listOf("점심", "밤샘 편집"), titles(s))
    }

    @Test fun turningOnHiddenCalendarShowsItsRecurringEvents() {
        val c = cache()
        val selection = DayBuilder.defaultSelection(c.calendars) + fixedId
        val s = DayBuilder.build(me, c, selection, ok, now)
        assertEquals(WidgetState.NORMAL, s.state)
        assertEquals(listOf("아침 운동", "점심", "영상 업로드", "밤샘 편집"), titles(s))
        val run = s.events.first { it.title == "아침 운동" }
        assertEquals(6 * 60 + 30, run.startMin)
        assertEquals(7 * 60 + 30, run.endMin)
        assertEquals(fixedId, run.calendarId)
        assertEquals("일정 색(colorId 11)", Palette.GOOGLE_EVENT["11"], s.events.first { it.title == "영상 업로드" }.color)
        assertEquals("다음 일정", "영상 업로드", s.next?.title)
        // 반복 일정의 내일 회차는 오늘에 들어가지 않음
        assertEquals(1, s.events.count { it.title == "아침 운동" })
    }

    @Test fun turningItOffAgainRemovesEvents() {
        val c = cache()
        val on = DayBuilder.defaultSelection(c.calendars) + fixedId
        val off = on - fixedId
        assertTrue("아침 운동" in titles(DayBuilder.build(me, c, on, ok, now)))
        assertFalse("아침 운동" in titles(DayBuilder.build(me, c, off, ok, now)))
        assertFalse("영상 업로드" in titles(DayBuilder.build(me, c, off, ok, now)))
    }

    @Test fun onlyHiddenCalendarSelected() {
        val s = DayBuilder.build(me, cache(), setOf(fixedId), ok, now)
        assertEquals(listOf("아침 운동", "영상 업로드"), titles(s))
        assertTrue(s.allDay.isEmpty())
    }

    @Test fun nothingSelected() {
        assertEquals(WidgetState.NO_CALENDAR, DayBuilder.build(me, cache(), emptySet(), ok, now).state)
    }

    @Test fun cancelledDeclinedAndWorkingLocationAreSkipped() {
        val c = cache()
        assertFalse(c.events.any { it.title == "거절한 회의" })
        assertFalse(c.events.any { it.title == "집" })
        assertEquals(2, c.events.count { it.title == "아침 운동" })   // 오늘·내일 (취소 회차 제외)
    }

    @Test fun eventCrossingMidnightIsClipped() {
        val s = DayBuilder.build(me, cache(), null, ok, now)
        val late = s.events.first { it.title == "밤샘 편집" }
        assertEquals(23 * 60, late.startMin)
        assertEquals(1440, late.endMin)
        val tomorrow = DayBuilder.build(me, cache(), null, ok, now.plusDays(1).withHour(0).withMinute(30))
        val lateTomorrow = tomorrow.events.first { it.title == "밤샘 편집" }
        assertEquals(0, lateTomorrow.startMin)
        assertEquals(60, lateTomorrow.endMin)
    }

    @Test fun allDayEventOnlyOnItsDay() {
        val s = DayBuilder.build(me, cache(), null, ok, now)
        assertEquals(listOf("가족 생일"), s.allDay.map { it.title })
        val tomorrow = DayBuilder.build(me, cache(), null, ok, now.plusDays(1))
        assertTrue(tomorrow.allDay.isEmpty())
    }

    @Test fun cachedTomorrowWorksOfflineAfterMidnight() {
        val selection = DayBuilder.defaultSelection(cache().calendars) + fixedId
        val s = DayBuilder.build(me, cache(), selection, SyncStatus(SyncError.NETWORK, 0), now.plusDays(1).withHour(6))
        assertTrue(s.offline)
        assertNotNull(s.staleSince)
        assertTrue("아침 운동" in titles(s))
    }

    @Test fun offlineShowsLastDataWithTimestamp() {
        val c = cache()
        val s = DayBuilder.build(me, c, null, SyncStatus(SyncError.NETWORK, 0), now)
        assertTrue(s.offline)
        assertEquals(c.fetchedAt, s.staleSince)
        assertEquals(listOf("점심", "밤샘 편집"), titles(s))
    }

    @Test fun oldDataIsMarkedStaleEvenOnline() {
        val c = cache(fetchedAt = now.toInstant().toEpochMilli() - 3 * 60 * 60_000)
        assertEquals(c.fetchedAt, DayBuilder.build(me, c, null, ok, now).staleSince)
        assertNull(DayBuilder.build(me, cache(), null, ok, now).staleSince)
    }

    @Test fun otherAccountCacheIsNeverShown() {
        val s = DayBuilder.build("other@gmail.com", cache(account = me), null, ok, now)
        assertEquals(WidgetState.NO_DATA, s.state)
        assertTrue(s.events.isEmpty())
    }

    @Test fun notConnected() {
        val s = DayBuilder.build(null, cache(), null, ok, now)
        assertEquals(WidgetState.NEEDS_CONNECT, s.state)
        assertTrue(s.events.isEmpty())
        assertTrue(s.needsApp)
    }

    @Test fun authExpiredKeepsLastEventsButAlerts() {
        val s = DayBuilder.build(me, cache(), null, SyncStatus(SyncError.AUTH, 0), now)
        assertEquals(Alert.AUTH_EXPIRED, s.alert)
        assertTrue(s.needsApp)
        assertEquals(listOf("점심", "밤샘 편집"), titles(s))
    }

    @Test fun outsideCachedRangeIsNoData() {
        val s = DayBuilder.build(me, cache(), null, SyncStatus(SyncError.NETWORK, 0), now.plusDays(3))
        assertEquals(WidgetState.NO_DATA, s.state)
        assertNotNull(s.staleSince)
    }

    @Test fun cacheRoundTrip() {
        val c = cache()
        val back = GoogleCalendarJson.cacheFromJson(GoogleCalendarJson.cacheToJson(c))
        assertEquals(c, back)
    }

    @Test fun miniJsonEscapes() {
        val v = mapOf("a" to "따옴표\" 역슬래시\\ 줄바꿈\n 탭\t", "n" to 12L, "d" to 1.5, "b" to true, "z" to null, "l" to listOf(1L, "x"))
        assertEquals(v, MiniJson.parse(MiniJson.write(v)))
        assertEquals("가", MiniJson.parse("\"\\uAC00\""))
    }

    @Test fun pagedCalendarListToken() {
        val (items, next) = GoogleCalendarJson.parseCalendarList("""{"items":[],"nextPageToken":"abc"}""")
        assertTrue(items.isEmpty())
        assertEquals("abc", next)
    }
}

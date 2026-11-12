package kr.kro.bappul.planner

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.LocalDate
import java.time.ZoneId

/**
 * 가짜 Calendar API 서버로 실제 HTTP 호출 흐름을 확인:
 * 숨긴 캘린더 포함 목록, 캘린더 ID 인코딩(#, @), 페이지 넘김, 토큰 헤더, 401 처리, 일부 캘린더 실패.
 */
class GoogleApiTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val today = LocalDate.of(2026, 11, 12)

    private fun <T> withServer(handler: (path: String, query: Map<String, String>, auth: String?) -> Pair<Int, String>, block: () -> T): T {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val raw = ex.requestURI.rawQuery ?: ""
            val q = raw.split("&").filter { it.isNotEmpty() }.associate {
                val (k, v) = (it.split("=", limit = 2) + "").take(2)
                k to URLDecoder.decode(v, "UTF-8")
            }
            val (code, body) = handler(ex.requestURI.rawPath, q, ex.requestHeaders.getFirst("Authorization"))
            val bytes = body.toByteArray(Charsets.UTF_8)
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        val old = GoogleApi.base
        GoogleApi.base = "http://127.0.0.1:${server.address.port}/calendar/v3"
        try { return block() } finally { GoogleApi.base = old; server.stop(0) }
    }

    @Test fun fetchesHiddenCalendarEventsWithPaging() {
        val seen = mutableListOf<String>()
        val (cals, events) = withServer({ path, q, auth ->
            seen += path
            assertEquals("Bearer tok", auth)
            when {
                path.endsWith("/users/me/calendarList") -> {
                    assertEquals("true", q["showHidden"])
                    200 to """{"items":[
                      {"id":"me@gmail.com","summary":"me","selected":true,"primary":true,"backgroundColor":"#9fc6e7"},
                      {"id":"fixed#1@group.calendar.google.com","summary":"고정 일정","hidden":true,"backgroundColor":"#b99aff"},
                      {"id":"gone@group.calendar.google.com","summary":"권한 없음","selected":true}]}"""
                }
                path.contains("/calendars/fixed%231%40group.calendar.google.com/events") -> {
                    assertEquals("true", q["singleEvents"])
                    assertEquals("2026-11-12T00:00:00+09:00", q["timeMin"])
                    assertEquals("2026-11-14T00:00:00+09:00", q["timeMax"])
                    if (q["pageToken"] == null) 200 to """{"items":[{"status":"confirmed","summary":"아침 운동","start":{"dateTime":"2026-11-12T06:30:00+09:00"},"end":{"dateTime":"2026-11-12T07:30:00+09:00"}}],"nextPageToken":"p2"}"""
                    else 200 to """{"items":[{"status":"confirmed","summary":"아침 운동","start":{"dateTime":"2026-11-13T06:30:00+09:00"},"end":{"dateTime":"2026-11-13T07:30:00+09:00"}}]}"""
                }
                path.contains("/calendars/me%40gmail.com/events") -> 200 to """{"items":[]}"""
                path.contains("/calendars/gone%40group.calendar.google.com/events") -> 404 to """{"error":{"code":404}}"""
                else -> 500 to "unexpected $path"
            }
        }) { GoogleApi.fetchAll("tok", today, today.plusDays(2), zone) }
        assertEquals(3, cals.size)
        assertTrue(cals.first { it.name == "고정 일정" }.hidden)
        assertEquals(2, events.size)   // 두 페이지
        assertTrue(events.all { it.calendarId == "fixed#1@group.calendar.google.com" })
        assertTrue(seen.any { it.contains("gone%40") })   // 실패한 캘린더는 건너뜀
    }

    @Test fun expiredTokenThrows401() {
        var code = 0
        withServer({ _, _, _ -> 401 to """{"error":{"code":401,"status":"UNAUTHENTICATED"}}""" }) {
            try { GoogleApi.fetchAll("old", today, today.plusDays(2), zone) } catch (e: HttpStatusException) { code = e.code }
        }
        assertEquals(401, code)
    }

    @Test fun eventCalendar401IsNotSwallowed() {
        var code = 0
        withServer({ path, _, _ ->
            if (path.endsWith("/calendarList")) 200 to """{"items":[{"id":"me@gmail.com","summary":"me","selected":true}]}"""
            else 401 to "{}"
        }) {
            try { GoogleApi.fetchAll("tok", today, today.plusDays(2), zone) } catch (e: HttpStatusException) { code = e.code }
        }
        assertEquals(401, code)
    }
}

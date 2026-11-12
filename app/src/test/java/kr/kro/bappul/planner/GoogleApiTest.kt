package kr.kro.bappul.planner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
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

    /** 아주 작은 HTTP 서버 (Android 단위 테스트 환경에는 com.sun.net.httpserver 가 없어 소켓으로 직접 구현) */
    private fun <T> withServer(handler: (path: String, query: Map<String, String>, auth: String?) -> Pair<Int, String>, block: () -> T): T {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: Exception) { break }
                socket.use { sock ->
                    val reader = sock.getInputStream().bufferedReader(Charsets.UTF_8)
                    val requestLine = reader.readLine() ?: return@use
                    var auth: String? = null
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Authorization:", ignoreCase = true)) auth = line.substringAfter(":").trim()
                    }
                    val target = requestLine.split(" ")[1]
                    val path = target.substringBefore("?")
                    val raw = target.substringAfter("?", "")
                    val q = raw.split("&").filter { it.isNotEmpty() }.associate {
                        val parts = it.split("=", limit = 2)
                        parts[0] to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
                    }
                    val (code, body) = handler(path, q, auth)
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    val out = sock.getOutputStream()
                    out.write(("HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                    out.write(bytes)
                    out.flush()
                }
            }
        }
        val old = GoogleApi.base
        GoogleApi.base = "http://127.0.0.1:${server.localPort}/calendar/v3"
        try { return block() } finally { GoogleApi.base = old; server.close(); worker.join(2000) }
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

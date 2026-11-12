package kr.kro.bappul.planner

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** HTTP 상태 코드 오류 (401: 토큰 만료, 403: 권한·API 문제 등). */
class HttpStatusException(val code: Int, val body: String) : IOException("HTTP $code")

/**
 * Google Calendar API v3 읽기 전용 호출. 읽기 권한(calendarlist.readonly, events.readonly)만 씀.
 * 네트워크 호출이라 반드시 백그라운드 스레드에서 부를 것.
 */
object GoogleApi {
    /** 테스트에서만 바꿈 */
    internal var base = "https://www.googleapis.com/calendar/v3"

    /**
     * 캘린더 목록 + 모든 캘린더(숨긴 것 포함)의 기간 안 일정을 한 번에 받음.
     * 위젯에서 캘린더를 켜고 끌 때 다시 받지 않아도 되도록 전부 받아 둠.
     * 캘린더 하나가 404/403 이면 그 캘린더만 건너뜀. 401(토큰 만료)은 그대로 던짐.
     */
    fun fetchAll(token: String, from: LocalDate, until: LocalDate, zone: ZoneId): Pair<List<GoogleCalendarInfo>, List<CachedEvent>> {
        val calendars = calendarList(token)
        val events = calendars.flatMap { cal ->
            try {
                events(token, cal, from, until, zone)
            } catch (e: HttpStatusException) {
                if (e.code == 401) throw e
                emptyList()
            }
        }
        return calendars to events
    }

    /** 캘린더 목록. showHidden=true 로 Google 캘린더에서 숨긴 캘린더도 받음. */
    fun calendarList(token: String): List<GoogleCalendarInfo> {
        val all = mutableListOf<GoogleCalendarInfo>()
        var pageToken: String? = null
        do {
            val url = "$base/users/me/calendarList?showHidden=true&minAccessRole=reader&maxResults=250" +
                (pageToken?.let { "&pageToken=" + enc(it) } ?: "")
            val (items, next) = GoogleCalendarJson.parseCalendarList(get(url, token))
            all += items
            pageToken = next
        } while (pageToken != null)
        return all
    }

    /** 한 캘린더의 기간 안 일정. singleEvents=true 라 반복 일정이 회차별로 펼쳐져서 옴. */
    fun events(token: String, calendar: GoogleCalendarInfo, from: LocalDate, until: LocalDate, zone: ZoneId): List<CachedEvent> {
        val all = mutableListOf<CachedEvent>()
        val timeMin = from.atStartOfDay(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val timeMax = until.atStartOfDay(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        var pageToken: String? = null
        do {
            val url = "$base/calendars/${enc(calendar.id)}/events?singleEvents=true&orderBy=startTime&maxResults=2500" +
                "&timeMin=${enc(timeMin)}&timeMax=${enc(timeMax)}&timeZone=${enc(zone.id)}" +
                "&fields=" + enc("items(status,summary,start,end,colorId,eventType,attendees(self,responseStatus)),nextPageToken") +
                (pageToken?.let { "&pageToken=" + enc(it) } ?: "")
            val (items, next) = GoogleCalendarJson.parseEvents(get(url, token), calendar.id, calendar.color)
            all += items
            pageToken = next
        } while (pageToken != null)
        return all
    }

    /** 연결 끊기: 토큰 철회 (실패해도 앱 쪽 데이터는 지움) */
    fun revoke(token: String) {
        val conn = URL("https://oauth2.googleapis.com/revoke?token=" + enc(token)).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { }
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    private fun get(url: String, token: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw HttpStatusException(code, body)
            return body
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

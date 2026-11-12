package kr.kro.bappul.planner

import java.time.OffsetDateTime

/** Google Calendar API 응답(JSON) ↔ 앱 모델 변환. Android 의존 없음. */
object GoogleCalendarJson {

    /** calendarList.list 응답 한 페이지 → (캘린더들, 다음 페이지 토큰) */
    fun parseCalendarList(json: String): Pair<List<GoogleCalendarInfo>, String?> {
        val root = MiniJson.obj(MiniJson.parse(json))
        val items = MiniJson.arr(root["items"]).mapIndexedNotNull { i, raw ->
            val o = MiniJson.obj(raw)
            val id = o["id"] as? String ?: return@mapIndexedNotNull null
            if (o["deleted"] == true) return@mapIndexedNotNull null
            GoogleCalendarInfo(
                id = id,
                name = (o["summaryOverride"] as? String)?.takeIf { it.isNotBlank() }
                    ?: (o["summary"] as? String)?.takeIf { it.isNotBlank() } ?: id,
                color = Palette.parseHex(o["backgroundColor"] as? String, Palette.FALLBACK[i % Palette.FALLBACK.size]),
                hidden = o["hidden"] == true,
                selectedInGoogle = o["selected"] == true,
                primary = o["primary"] == true,
            )
        }
        return items to (root["nextPageToken"] as? String)
    }

    /**
     * events.list(singleEvents=true) 응답 한 페이지 → (일정들, 다음 페이지 토큰).
     * 반복 일정은 서버가 회차별로 펼쳐서 줌. 취소된 회차·내가 거절한 초대·근무 위치 표시는 뺌.
     */
    fun parseEvents(json: String, calendarId: String, calendarColor: Int): Pair<List<CachedEvent>, String?> {
        val root = MiniJson.obj(MiniJson.parse(json))
        val items = MiniJson.arr(root["items"]).mapNotNull { raw ->
            val o = MiniJson.obj(raw)
            if (o["status"] == "cancelled") return@mapNotNull null
            if (o["eventType"] == "workingLocation") return@mapNotNull null
            val declined = MiniJson.arr(o["attendees"]).any {
                val a = MiniJson.obj(it); a["self"] == true && a["responseStatus"] == "declined"
            }
            if (declined) return@mapNotNull null
            val title = (o["summary"] as? String)?.takeIf { it.isNotBlank() } ?: "제목 없는 일정"
            val color = Palette.GOOGLE_EVENT[o["colorId"] as? String] ?: calendarColor
            val start = MiniJson.obj(o["start"])
            val end = MiniJson.obj(o["end"])
            val startDate = start["date"] as? String
            if (startDate != null) {
                CachedEvent(calendarId, title, true, 0L, 0L, startDate, (end["date"] as? String) ?: startDate, color)
            } else {
                val s = start["dateTime"] as? String ?: return@mapNotNull null
                val e = end["dateTime"] as? String ?: return@mapNotNull null
                val sMs = OffsetDateTime.parse(s).toInstant().toEpochMilli()
                val eMs = OffsetDateTime.parse(e).toInstant().toEpochMilli()
                if (eMs <= sMs) return@mapNotNull null
                CachedEvent(calendarId, title, false, sMs, eMs, null, null, color)
            }
        }
        return items to (root["nextPageToken"] as? String)
    }

    // ── 캐시 저장 형식 ──

    fun cacheToJson(c: CalendarCache): String = MiniJson.write(mapOf(
        "version" to 1,
        "account" to c.account,
        "fetchedAt" to c.fetchedAt,
        "rangeStart" to c.rangeStart,
        "rangeEnd" to c.rangeEnd,
        "calendars" to c.calendars.map {
            mapOf("id" to it.id, "name" to it.name, "color" to Palette.toHex(it.color),
                "hidden" to it.hidden, "selected" to it.selectedInGoogle, "primary" to it.primary)
        },
        "events" to c.events.map {
            mapOf("cal" to it.calendarId, "title" to it.title, "allDay" to it.allDay,
                "s" to it.startMs, "e" to it.endMs, "sd" to it.startDate, "ed" to it.endDate, "color" to Palette.toHex(it.color))
        },
    ))

    fun cacheFromJson(json: String): CalendarCache? = try {
        val o = MiniJson.obj(MiniJson.parse(json))
        CalendarCache(
            account = o["account"] as String,
            fetchedAt = (o["fetchedAt"] as Number).toLong(),
            rangeStart = o["rangeStart"] as String,
            rangeEnd = o["rangeEnd"] as String,
            calendars = MiniJson.arr(o["calendars"]).map {
                val c = MiniJson.obj(it)
                GoogleCalendarInfo(c["id"] as String, c["name"] as String, Palette.parseHex(c["color"] as? String, Palette.ACCENT),
                    c["hidden"] == true, c["selected"] == true, c["primary"] == true)
            },
            events = MiniJson.arr(o["events"]).map {
                val e = MiniJson.obj(it)
                CachedEvent(e["cal"] as String, e["title"] as String, e["allDay"] == true,
                    (e["s"] as Number).toLong(), (e["e"] as Number).toLong(),
                    e["sd"] as? String, e["ed"] as? String, Palette.parseHex(e["color"] as? String, Palette.ACCENT))
            },
        )
    } catch (e: Exception) {
        null
    }
}

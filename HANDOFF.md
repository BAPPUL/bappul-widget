# 인수인계 메모 — 오늘의 생활계획표 (2026-11-12 기준)

## 1. 웹사이트 (`bappul-site`, https://www.bappul.kro.kr/)
PC 폴더: `C:\Users\user\Documents\Codex\2026-11-12\g\work\bappul-site\` — 원본 `index.html`은 그대로 두고, 아래 파일들은 같은 폴더에 새로 만듦 (**배포 안 함**)
| 파일 | 내용 |
|---|---|
| `생활계획표_설정_UI.html` | 설정 서랍: 시간 표시(15:00/오후 3:00), 원형 시간표 스타일(기본·심플·클래식), 테마(시스템·라이트·다크), 글자 크기, 지난 일정 흐리게 — localStorage 저장 |
| `생활계획표_기능추가.html` | + 조각 끌어서 시간 바꾸기(Google 저장·되돌리기), 다음 일정 알림, 주간 보기, 휠·단축키, 반복 일정 추가, 애니메이션 |
| `생활계획표_모바일.html` | **최신.** + 휴대폰 아래 탭 바(시간표/일정/할 일), 좌우로 밀어 날짜 이동, 꾹 눌러 끌기 |
- 배포하려면 `생활계획표_모바일.html` 내용을 `index.html`로 바꿔서 올리면 됨 (실제 Google 로그인 동작은 미확인)

## 2. Android 위젯 (`BAPPUL/bappul-widget`, GitHub 비공개)
- 2×2·4×4 홈 화면 위젯, Kotlin. 현재 **v0.2.1 (versionCode 3)**
- Google Calendar API 직접 연동(읽기 범위 2개), Google에서 숨긴 캘린더도 위젯에서만 켜기, 오프라인 캐시·"HH:mm 기준", 연결 만료 표시, 계정 바꾸면 이전 일정 삭제
- `main`에 push → GitHub Actions가 테스트 21개 + APK 빌드 → **Releases**에 APK 업로드
- 최신 APK: https://github.com/BAPPUL/bappul-widget/releases/download/v0.2.1-build5/bappul-widget-v0.2.1-build5.apk
- 서명: 위젯 전용 키(`planner-release.jks`, 별칭 `planner`), GitHub Secrets `KEYSTORE_BASE64`·`KEYSTORE_PASSWORD`
  - SHA-1 `93:47:CA:71:D6:96:2A:6F:BC:4B:86:BE:CE:46:6E:92:62:A0:A0:67`
  - 키 파일·비밀번호 백업 필수 (잃으면 덮어쓰기 설치 불가)
- 자세한 설계·빌드·확인 목록: `README.md`

## 3. 지금 막힌 곳 — Google 계정 연결 시 "코드 8"
코드 8 = Google Play 서비스 INTERNAL_ERROR. 앱 코드보다 Google Cloud 설정 문제일 가능성이 큼. v0.2.1부터 앱이 "자세한 내용"(Google 오류 문구)을 보여주니 먼저 그걸 확인.

Google Cloud (프로젝트 Local Day Planner `mystic-hull-514416-f0`) 확인 순서:
1. 클라이언트에 **Android 유형** 클라이언트: 패키지 `kr.kro.bappul.planner`, SHA-1 위 값 (웹 클라이언트와 별개)
2. 브랜딩: 앱 이름·사용자 지원 이메일·개발자 연락처 모두 입력
3. 대상: 테스트 상태면 테스트 사용자에 본인 Gmail
4. 데이터 액세스: `https://www.googleapis.com/auth/calendar.calendarlist.readonly`, `https://www.googleapis.com/auth/calendar.events.readonly`
5. 설정 변경 후 5~10분 대기, 휴대폰 Google Play 서비스 업데이트

그래도 안 되면 의심할 코드: `GoogleAuth.kt`의 `AuthorizationRequest`(play-services-auth 21.3.0 → 최신 21.6.0+로 올려보기), `MainActivity.connect()`.

## 4. 남은 확인 (실기기)
- [ ] Google 연결 성공 → 캘린더 목록에 "고정 일정 (Google 캘린더에서 숨김)" 표시
- [ ] 위젯에서만 켜면 2×2·4×4에 일정·반복 일정 표시, 끄면 사라짐
- [ ] 비행기 모드에서 마지막 일정 + "오프라인 · HH:mm 기준"
- [ ] 계정 바꾸기/연결 끊기 후 이전 일정 사라짐
- [ ] 위젯 터치 → 사이트 열림, 연결 만료 시 → 앱 열림

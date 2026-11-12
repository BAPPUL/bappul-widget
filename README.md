# 오늘의 생활계획표 — 갤럭시 홈 화면 위젯 (Android, Kotlin) · v0.2.0

`https://www.bappul.kro.kr/` 생활계획표를 홈 화면에서 바로 보는 위젯입니다. **4×4**와 **2×2** 두 크기가 있습니다.
v0.2.0부터 휴대폰 캘린더 대신 **Google Calendar API**에서 일정을 직접 가져옵니다. 그래서 Google 캘린더 앱에서 숨겨둔 캘린더(예: "고정 일정")도 위젯에서만 켤 수 있습니다.

> **상태: 소스 완성, APK 빌드·실기기 확인 전.**
> 작성 환경에 Android SDK·adb가 없고 Google/Maven 저장소에 접속할 수 없어 APK를 만들지 못했습니다.
> 대신 앱 코드 전체를 Kotlin 컴파일러로 컴파일(Android API 대체 클래스 사용)했고, 핵심 로직은 JVM 단위 테스트 21개로 확인했습니다. 자세한 내용은 맨 아래 "확인한 것"에 있습니다.

## 미리보기
- `preview/home_mockup.png`: 홈 화면에 4×4와 2×2를 놓은 모습.
- `preview/states.png`: 4×4·2×2 각각 평소(숨긴 '고정 일정'을 위젯에서만 켬) / 인터넷 끊김 / 연결 만료 / 계정 연결 전.
- 목업은 `preview/mockup.html`(위젯 그리기 규칙을 그대로 옮긴 HTML)에서 만든 이미지입니다. 실기기 캡처가 아닙니다.

## v0.2.0 바뀐 점
| | v0.1 | v0.2 |
|---|---|---|
| 일정 출처 | 휴대폰 캘린더(READ_CALENDAR) — 숨긴 캘린더는 안 보임 | **Google Calendar API**. 숨긴 캘린더 포함 |
| 권한 | 캘린더 권한 | Google 계정 연결, 읽기 전용 범위 2개 (`calendar.calendarlist.readonly`, `calendar.events.readonly`) |
| 캘린더 선택 | 휴대폰 캘린더 목록 | Google 캘린더 목록 전체(숨긴 것 포함). **계정별로 위젯 전용 선택을 따로 저장** |
| 오프라인 | — | 같은 계정의 마지막 일정 + "오프라인 · 13:05 기준" 표시 |
| 연결 문제 | — | "Google 연결이 만료됐어요 · 다시 연결"(코랄), 누르면 앱에서 다시 연결 |
| 버전 | versionCode 1 | **versionCode 2** (덮어쓰기 설치) |

디자인(색, 원형 시간표, 2×2·4×4 배치)과 "위젯을 누르면 사이트 열기"는 그대로입니다.

## 동작
### 계정 연결
- 앱 화면의 **Google 계정 연결**을 누르면 Google Identity Services(`AuthorizationClient`)가 계정을 고르고 권한을 묻습니다.
- 앱에 OAuth 클라이언트 ID를 넣지 않습니다. Google Cloud의 **Android OAuth 클라이언트**(패키지 이름 + 서명 SHA-1)로 앱을 알아봅니다. 사용자가 클라이언트 ID를 입력하는 칸도 없습니다.
- 한 번 허용하면 이후에는 화면 없이 새 토큰을 받아 백그라운드에서 갱신합니다. 앱에 비밀번호나 리프레시 토큰을 저장하지 않습니다.

### 캘린더 선택 (숨긴 캘린더)
- `calendarList?showHidden=true`로 Google 캘린더에서 숨긴 캘린더까지 목록에 보여줍니다. 숨긴 캘린더에는 "Google 캘린더에서 숨김"이라고 표시합니다.
- 위젯 선택은 앱 안에 계정별로 저장하고, Google 캘린더 앱의 표시/숨김과 따로 움직입니다. 처음에는 Google에서 보이는 캘린더만 켜져 있습니다.
- 일정은 모든 캘린더(숨긴 것 포함)를 오늘·내일 이틀치 받아 둡니다. 그래서 캘린더를 켜고 끄면 네트워크 없이 바로 2×2·4×4가 다시 그려집니다.
- 반복 일정은 `singleEvents=true`로 서버가 회차별로 펼쳐줍니다. 취소된 회차, 내가 거절한 초대, 근무 위치 표시는 빼고, 일정 색(colorId)이 있으면 캘린더 색보다 먼저 씁니다.

### 갱신
| 언제 | 무엇을 |
|---|---|
| 계정 연결·다시 연결 직후 | 바로 받아서 2×2·4×4 갱신 |
| 위젯 캘린더 선택 변경 | 저장된 일정으로 바로 다시 그림 (네트워크 불필요) |
| 앱을 열 때 / "지금 새로고침" | 바로 받기 |
| 30분마다 (인터넷 연결 시) | WorkManager 주기 작업 |
| 5분마다 (화면 켜짐) | 저장된 일정으로 다시 그림 (현재 시각 바늘·다음 일정·자정 넘김). 30분 넘게 지난 일정이면 받기 요청 |
| 매분 | 가운데 시계(TextClock)는 저절로 바뀜 |

### 오프라인·계정 변경
- 인터넷이 끊기면 같은 계정의 마지막 일정을 그대로 보여주고, 4×4는 "오프라인 · 13:05 기준", 2×2는 "13:05 기준"을 표시합니다. 데이터가 45분보다 오래돼도 "기준" 시각이 나옵니다.
- 이틀치를 받아 두므로 자정이 지나도 오프라인으로 다음 날을 보여줄 수 있습니다.
- 캐시에는 계정 이메일이 함께 저장되고, 다른 계정의 캐시는 절대 그리지 않습니다.
- **다른 계정으로 바꾸기**를 하면 이전 일정을 바로 지우고, **연결 끊기**를 하면 일정을 지우고 토큰을 철회합니다. 연결을 끊은 뒤 위젯에는 "Google 계정을 연결해 주세요"만 남습니다.

### 위젯 표시와 누르기
| 상태 | 4×4 아래 줄 | 2×2 가운데 | 누르면 |
|---|---|---|---|
| 평소 | ● 다음 일정 이름 · 시작 시간 | 다음 · 시간 / 이름 | 사이트 |
| 인터넷 끊김 | 다음 일정 + 가운데 "오프라인 · HH:mm 기준" | + "HH:mm 기준" | 사이트 |
| 연결 만료 | **Google 연결이 만료됐어요 · 다시 연결**(코랄), 마지막 일정은 유지 | **연결 만료 / 다시 연결** | 앱 |
| 권한 일부만 허용 | 캘린더 읽기 권한이 필요해요 · 허용하기 | 권한 필요 | 앱 |
| 계정 연결 전 | Google 계정을 연결해 주세요 · 연결 (점선 링) | Google 연결 | 앱 |
| 캘린더를 하나도 안 고름 | 표시할 캘린더를 골라 주세요 | 캘린더 선택 | 앱 |
| 연결 직후·오래 오프라인 | 일정을 불러오는 중… / 인터넷에 연결되면 일정을 불러와요 | 불러오는 중… / 오프라인 | 앱 |

## 내가 해야 할 설정 (Google Cloud) — 한 번만

프로젝트: **Local Day Planner** (`mystic-hull-514416-f0`), 패키지 이름: **`kr.kro.bappul.planner`**
웹사이트용 OAuth 클라이언트는 그대로 두고, Android용 클라이언트를 **따로 하나 더** 만듭니다.

1. **설치할 APK의 서명 SHA-1 확인** (아래 "빌드" 1번에서 같이 나옵니다)
   - `gradlew.bat :app:signingReport` → `Variant: debug` 아래 `SHA1: AA:BB:…`
   - 또는 설치 후 앱 맨 아래 **앱 정보(Google Cloud 설정용)** 카드를 누르면 SHA-1이 복사됩니다.
2. https://console.cloud.google.com/ → 위쪽 프로젝트 선택에서 **Local Day Planner**
3. **API 및 서비스 › 사용 설정된 API**에서 **Google Calendar API**가 켜져 있는지 확인합니다(웹사이트 때문에 이미 켜져 있을 것). 없으면 **라이브러리**에서 검색해 사용 설정합니다.
4. **Google 인증 플랫폼(OAuth 동의 화면) › 데이터 액세스 › 범위 추가 또는 삭제**에서 아래 두 범위를 체크하고 **업데이트 → 저장**합니다.
   - `https://www.googleapis.com/auth/calendar.calendarlist.readonly`
   - `https://www.googleapis.com/auth/calendar.events.readonly`
5. 같은 메뉴의 **대상**에서 게시 상태가 "테스트"이면 **테스트 사용자**에 위젯에 연결할 Gmail 주소가 있는지 확인하고, 없으면 추가합니다.
6. **클라이언트 › + 클라이언트 만들기**
   - 애플리케이션 유형: **Android**
   - 이름: `생활계획표 위젯 (Android)`
   - 패키지 이름: `kr.kro.bappul.planner`
   - SHA-1 인증서 디지털 지문: 1번에서 확인한 값
   - **만들기**를 누릅니다. 클라이언트 ID는 앱에 넣을 필요가 없습니다.
7. 반영까지 몇 분 걸릴 수 있습니다. 앱에서 **Google 계정 연결**을 눌렀는데 "Android OAuth 클라이언트가 아직 없어요(코드 10)"가 나오면, 패키지 이름과 SHA-1이 6번 값과 같은지 확인하세요.

> 웹사이트 배포는 이 작업에 필요하지 않습니다.

## 빌드
준비물: JDK 17 이상, Android SDK 35(Android Studio 설치 시 함께), 인터넷.

```bat
:: bappul-widget 폴더에서 (Windows)
echo sdk.dir=C:\\Users\\user\\AppData\\Local\\Android\\Sdk> local.properties
gradlew.bat :app:signingReport          :: 서명 SHA-1 확인 (Google Cloud 6번에 사용)
gradlew.bat :app:testDebugUnitTest      :: 단위 테스트 21개
gradlew.bat :app:assembleDebug          :: APK 만들기
```
결과물: **`app\build\outputs\apk\debug\app-debug.apk`** (versionCode 2, versionName 0.2.0)

### 기존 설치본 덮어쓰기 (서명 키)
- Android는 **같은 서명 키**일 때만 덮어쓰기 설치를 허용합니다. 이 프로젝트는 기본적으로 빌드하는 PC의 디버그 키(`%USERPROFILE%\.android\debug.keystore`)로 서명합니다. **이전 APK를 만든 PC(예: Codex를 실행한 PC)에서 그대로 빌드하면 같은 키**라 덮어쓸 수 있습니다.
- 키가 같은지 미리 확인하려면, 이전 APK와 새 APK를 비교하세요: `apksigner verify --print-certs 이전.apk` 와 `... app-debug.apk` 의 `SHA-1 digest`가 같아야 합니다(`apksigner`는 `Sdk\build-tools\35.x\`에 있음). 또는 휴대폰에서 `adb shell dumpsys package kr.kro.bappul.planner | findstr -i signatures`로 볼 수 있습니다.
- 다른 PC에서 빌드해야 하면 그 디버그 키를 복사해 와서 `keystore.properties`(아래 예시)로 지정하세요. 키를 잃어버렸다면 기존 앱을 지우고 새로 설치해야 하고, 위젯도 다시 놓아야 합니다.
  ```properties
  # bappul-widget/keystore.properties (git 에 올리지 않음)
  storeFile=keys/debug.keystore
  storePassword=android
  keyAlias=androiddebugkey
  keyPassword=android
  ```
- 서명 키가 바뀌면 Google Cloud의 Android 클라이언트 SHA-1도 그 키 값으로 맞춰야 합니다.

## 갤럭시에 설치 (업데이트)
- **USB:** 개발자 옵션에서 USB 디버깅을 켜고 `adb install -r app\build\outputs\apk\debug\app-debug.apk`
  - `INSTALL_FAILED_UPDATE_INCOMPATIBLE`이 나오면 서명 키가 다른 것입니다(위 "덮어쓰기" 참고).
- **파일로:** APK를 휴대폰으로 옮겨 열고 "출처를 알 수 없는 앱 설치"를 허용합니다.
- 업데이트 후 이미 놓인 위젯은 "Google 계정을 연결해 주세요"로 바뀝니다. 위젯을 누르면 앱이 열리고 거기서 연결하면 됩니다.

## 가장 중요한 확인 (실기기)
1. Google 캘린더 앱에서 **고정 일정** 캘린더를 숨깁니다(오늘 일정 하나와 반복 일정 하나가 들어 있게).
2. 위젯 앱 → **Google 계정 연결** → 계정 선택 → 두 권한을 허용합니다.
3. **표시할 캘린더**에 "고정 일정 / Google 캘린더에서 숨김"이 보이는지, 처음엔 꺼져 있는지 확인합니다.
4. 체크를 켜면 앱 안 4×4·2×2 미리보기와 홈 화면 위젯에 그 일정과 반복 일정 회차가 바로 나타나는지 확인합니다.
5. 체크를 끄면 바로 빠지는지 확인합니다.
6. 비행기 모드에서 위젯에 "오프라인 · HH:mm 기준"과 마지막 일정이 남는지 확인합니다.
7. 다른 계정으로 바꾸거나 연결을 끊었을 때 이전 일정이 사라지는지 확인합니다.
8. 위젯을 누르면 사이트가 열리고, 연결 만료 상태에서 누르면 앱이 열리는지 확인합니다.

## 폴더 구조 (주요 파일)
```
app/src/main/java/kr/kro/bappul/planner/
  Model.kt               DayEvent, GoogleCalendarInfo, CachedEvent, CalendarCache, SyncStatus, WidgetState, Alert, TodaySnapshot, Palette
  MiniJson.kt            작은 JSON 파서/작성기 (JVM 테스트 가능)
  GoogleCalendarJson.kt  Calendar API 응답 → 모델, 캐시 저장 형식
  GoogleApi.kt           Calendar API v3 읽기 호출 (calendarList showHidden, events singleEvents, 페이지 넘김), 토큰 철회
  DayBuilder.kt          캐시 + 위젯 선택 + 상태 → 오늘 화면 데이터 (숨긴 캘린더·반복·오프라인·계정 구분 로직)
  GoogleAuth.kt          AuthorizationClient: 읽기 범위 요청, 화면 없는 토큰, 토큰 지우기
  GoogleSync.kt          받기 → 저장 → 위젯 갱신, 계정 연결·끊기
  Prefs.kt / CacheStore.kt  계정·계정별 위젯 선택·동기화 상태 / 마지막 일정 파일
  WidgetUpdater.kt       4×4·2×2 RemoteViews, 상태별 문구, "기준" 표시, 누르기 동작
  WheelRenderer.kt       원형 시간표 그리기 (변경 없음, 점선 조건만 새 상태로)
  RefreshScheduler.kt    30분 주기 받기, 즉시 받기, 5분 다시 그리기 / RefreshWorker
  PlannerWidgetProvider.kt  4×4·2×2 위젯 리시버
  MainActivity.kt        계정 연결/바꾸기/끊기, 캘린더 선택, 미리보기, 앱 정보(SHA-1)
app/src/test/.../DayBuilderTest.kt, GoogleApiTest.kt   단위 테스트
```
- minSdk 26, target/compileSdk 35, AGP 8.9.1, Kotlin 2.1.20
- 의존성: `work-runtime-ktx 2.9.1`, `play-services-auth 21.3.0`, (테스트) `junit 4.13.2`
- 권한: `INTERNET`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED`. 캘린더 권한(READ_CALENDAR)은 더 이상 쓰지 않습니다.

## 확인한 것 / 확인하지 못한 것
### 확인한 것
- **앱 코드 전체 컴파일:** `app/src/main/java`의 Kotlin 15개 파일을 Kotlin 2.0.21 컴파일러로 컴파일해 오류 0개를 확인했습니다. Android·Play 서비스·WorkManager는 실제 API 모양대로 만든 대체 클래스를 썼습니다. 실제 SDK와 메서드 모양이 다를 수 있어서, Gradle 빌드는 별도로 확인이 필요합니다.
- **단위 테스트 21개 통과** (`DayBuilderTest` 18, `GoogleApiTest` 3). 실제 Calendar API 응답 모양의 JSON과 가짜 HTTP 서버로 확인했습니다.
  - 숨긴 '고정 일정'이 목록에 보이고 기본은 꺼짐.
  - 위젯에서 켜면 반복 일정 회차("아침 운동" 06:30–07:30, "영상 업로드" 20:00–21:00)가 오늘 시간표에 들어가고, 내일 회차는 빠짐.
  - 다시 끄면 빠짐. 숨긴 캘린더만 켰을 때도 동작.
  - 취소 회차·거절한 초대·근무 위치는 빠짐. 자정을 넘는 일정은 잘라서 표시하고, 종일 일정은 해당 날에만 표시.
  - 오프라인이면 마지막 일정과 받은 시각, 오래된 데이터 표시, 자정 이후 오프라인으로 내일 표시, 받아둔 기간을 벗어나면 "불러오지 못함".
  - 다른 계정 캐시는 표시 안 함, 연결 전·연결 만료 상태, 캐시 저장/읽기 왕복.
  - HTTP: 숨긴 캘린더 포함 목록 요청(`showHidden=true`), 캘린더 ID 인코딩(`#`, `@`), 페이지 넘김, 토큰 헤더, 401 전달, 일부 캘린더 404는 건너뜀.
- XML 리소스 문법과 모든 리소스 참조 확인. 상태별 목업 렌더링.

### 확인하지 못한 것
- [ ] Gradle 빌드, APK 생성, 서명 SHA-1 (작성 환경에 SDK·저장소 없음)
- [ ] 이전 설치본과 같은 서명 키인지 (이전 키가 있는 PC에서 확인)
- [ ] Google Cloud Android 클라이언트 등록 후 실제 로그인·권한 화면·화면 없는 토큰 갱신
- [ ] 실기기: 업데이트 설치, 위젯 추가, 숨긴 캘린더, 반복 일정, 위젯 터치, 오프라인, 계정 바꾸기 (위 "가장 중요한 확인" 1~8)
- [ ] "다른 계정으로 바꾸기"에서 계정 선택 화면이 항상 뜨는지. 기기에 따라 이전 계정으로 바로 진행될 수 있고, 그때는 "연결 끊기" 후 다시 연결하세요.

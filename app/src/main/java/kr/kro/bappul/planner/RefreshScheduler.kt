package kr.kro.bappul.planner

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 갱신 예약.
 * - 30분마다 (인터넷 연결 시): WorkManager 주기 작업이 Google 일정을 새로 받음
 * - 계정 연결·캘린더 선택 변경·앱 열기: 바로 1회 받기 (syncNow)
 * - 5분마다 (화면이 켜져 있을 때): 저장된 일정으로 위젯만 다시 그림 (현재 시각 바늘, 다음 일정, 자정 넘김)
 * 가운데 숫자 시계는 TextClock 이라 이 예약과 상관없이 매분 바뀜.
 */
object RefreshScheduler {
    /** 이전 버전과 같은 이름을 써서 업데이트 설치 시 예전 예약을 덮어씀 */
    private const val PERIODIC = "planner-periodic"
    private const val NOW = "planner-sync-now"
    /** 이전 버전(휴대폰 캘린더 변경 감지)의 예약. 더 이상 쓰지 않아 취소함 */
    private const val LEGACY_CONTENT = "planner-calendar-change"
    private const val TICK_MINUTES = 5
    /** 이보다 오래된 일정이면 5분 알람 때 새로 받기를 요청 */
    private const val SYNC_IF_OLDER_MS = 30L * 60 * 1000

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun ensure(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(LEGACY_CONTENT)
        wm.enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<RefreshWorker>(30, TimeUnit.MINUTES).setConstraints(online).build(),
        )
    }

    /** 지금 바로 Google 일정 받기 (인터넷이 없으면 연결될 때 실행) */
    fun syncNow(context: Context, replace: Boolean = true) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            NOW, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<RefreshWorker>().setConstraints(online).build(),
        )
    }

    /** 저장된 일정이 오래됐으면 새로 받기 요청 */
    fun syncIfStale(context: Context) {
        if (Prefs.account(context) == null) return
        val cache = CacheStore.load(context)
        if (cache == null || System.currentTimeMillis() - cache.fetchedAt > SYNC_IF_OLDER_MS) syncNow(context, replace = false)
    }

    /** 다음 5분 정각(…:00, :05, :10…)에 위젯을 다시 그림. RTC 알람이라 화면이 꺼져 있으면 깨우지 않음. */
    fun scheduleTick(context: Context, active: Boolean) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, PlannerWidgetProvider::class.java).setAction(PlannerWidgetProvider.ACTION_REFRESH)
        val pending = PendingIntent.getBroadcast(context, 1, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (!active) { alarm.cancel(pending); return }
        val next = Calendar.getInstance().apply {
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.MINUTE, TICK_MINUTES - get(Calendar.MINUTE) % TICK_MINUTES)
        }
        alarm.setWindow(AlarmManager.RTC, next.timeInMillis, 30_000L, pending)
    }

    fun cancel(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(PERIODIC)
        wm.cancelUniqueWork(NOW)
        wm.cancelUniqueWork(LEGACY_CONTENT)
        scheduleTick(context, false)
    }
}

/** Google 일정을 받아 저장하고 위젯을 다시 그림. (클래스 이름은 이전 버전 예약과 맞추려고 그대로 둠) */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        GoogleSync.sync(applicationContext)
        return Result.success()
    }
}

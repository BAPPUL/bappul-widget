package kr.kro.bappul.planner

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import kotlin.concurrent.thread

/** 홈 화면 위젯(4×4). 저장된 일정 읽기·그리기는 메인 스레드를 막지 않도록 백그라운드에서 함. */
open class PlannerWidgetProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REFRESH,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> runAsync {
                WidgetUpdater.updateAll(context)
                if (intent.action != ACTION_REFRESH) RefreshScheduler.ensure(context)
                RefreshScheduler.syncIfStale(context)
            }
            else -> super.onReceive(context, intent)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        runAsync {
            WidgetUpdater.update(context, ids)
            RefreshScheduler.ensure(context)
            RefreshScheduler.scheduleTick(context, true)
            RefreshScheduler.syncIfStale(context)
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        runAsync { WidgetUpdater.update(context, intArrayOf(id)) }
    }

    override fun onEnabled(context: Context) {
        RefreshScheduler.ensure(context)
    }

    override fun onDisabled(context: Context) {
        // 다른 크기 위젯이 아직 홈 화면에 있으면 예약을 유지
        if (WidgetUpdater.widgetIds(context).isEmpty()) RefreshScheduler.cancel(context)
    }

    /** 브로드캐스트 처리를 백그라운드 스레드로 넘김 (goAsync 로 수명 연장) */
    private fun runAsync(block: () -> Unit) {
        val pending = goAsync()
        thread(name = "planner-widget") {
            try { block() } finally { pending.finish() }
        }
    }

    companion object {
        const val ACTION_REFRESH = "kr.kro.bappul.planner.action.REFRESH"
    }
}

/** 2×2 위젯. 동작은 같고, 화면만 WidgetUpdater.buildSmallViews() 로 그림. */
class PlannerWidgetSmallProvider : PlannerWidgetProvider()

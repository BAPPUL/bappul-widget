package kr.kro.bappul.planner

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import java.security.MessageDigest
import kotlin.concurrent.thread
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 앱을 열면 보이는 설정 화면.
 * - Google 계정 연결 / 다시 연결 / 다른 계정으로 바꾸기 / 연결 끊기
 * - 위젯에 표시할 캘린더 고르기 (Google 에서 숨긴 캘린더 포함, 위젯 전용으로 따로 저장)
 * - 4×4·2×2 위젯 미리보기 (실제 위젯과 같은 RemoteViews)
 * - 홈 화면에 위젯 추가, 지금 새로고침, 사이트 열기
 * - 앱 정보: Google Cloud 의 Android OAuth 클라이언트 등록에 필요한 패키지 이름·서명 SHA-1
 * 위젯을 길게 눌러 "설정"을 고르거나, 연결이 만료된 위젯을 눌러도 이 화면이 열림.
 */
class MainActivity : Activity() {

    private var configureWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var previewBox: FrameLayout
    private lateinit var previewSmallBox: FrameLayout
    private lateinit var accountText: TextView
    private lateinit var syncText: TextView
    private lateinit var errorText: TextView
    private lateinit var connectButton: Button
    private lateinit var switchButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var calendarList: LinearLayout
    private lateinit var calendarHint: TextView
    private val density get() = resources.displayMetrics.density
    @Volatile private var syncing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 위젯 추가·재설정으로 열린 경우: 바로 OK 를 돌려줘서 뒤로 가기만 해도 위젯이 추가되게 함
        configureWidgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (configureWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, configureWidgetId))
            findViewById<Button>(R.id.done).visibility = View.VISIBLE
        }

        previewBox = findViewById(R.id.preview_box)
        previewSmallBox = findViewById(R.id.preview_small_box)
        accountText = findViewById(R.id.account_text)
        syncText = findViewById(R.id.sync_text)
        errorText = findViewById(R.id.error_text)
        connectButton = findViewById(R.id.connect_button)
        switchButton = findViewById(R.id.switch_account)
        disconnectButton = findViewById(R.id.disconnect)
        calendarList = findViewById(R.id.calendar_list)
        calendarHint = findViewById(R.id.calendar_hint)

        connectButton.setOnClickListener { connect(chooseAccount = false) }
        switchButton.setOnClickListener { connect(chooseAccount = true) }
        disconnectButton.setOnClickListener { confirmDisconnect() }
        findViewById<Button>(R.id.pin_widget).setOnClickListener { pinWidget(PlannerWidgetProvider::class.java) }
        findViewById<Button>(R.id.pin_widget_small).setOnClickListener { pinWidget(PlannerWidgetSmallProvider::class.java) }
        findViewById<Button>(R.id.refresh_now).setOnClickListener { syncInBackground(showResult = true) }
        findViewById<Button>(R.id.open_site).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(WidgetUpdater.SITE_URL)))
        }
        findViewById<Button>(R.id.done).setOnClickListener { finish() }
        findViewById<TextView>(R.id.select_all).setOnClickListener {
            Prefs.account(this)?.let { Prefs.resetSelection(this, it) }
            applySelectionChange()
        }
        renderSigningInfo()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
        // 앱을 열 때마다 일정을 새로 받음 (오프라인이면 마지막 일정 유지)
        if (Prefs.account(this) != null) {
            RefreshScheduler.ensure(this)
            syncInBackground(showResult = false)
        }
    }

    // ── Google 계정 연결 ──

    /** @param chooseAccount true 면 계정 선택 화면을 띄움 (다른 계정으로 바꾸기) */
    private fun connect(chooseAccount: Boolean) {
        showError(null)
        val account = if (chooseAccount) null else Prefs.account(this)
        Identity.getAuthorizationClient(this).authorize(GoogleAuth.request(account))
            .addOnSuccessListener { result ->
                val pending = result.pendingIntent
                if (result.hasResolution() && pending != null) {
                    try {
                        startIntentSenderForResult(pending.intentSender, REQUEST_AUTH, null, 0, 0, 0, null)
                    } catch (e: Exception) {
                        showError(getString(R.string.err_generic, e.javaClass.simpleName))
                    }
                } else onAuthorized(result)
            }
            .addOnFailureListener { e -> showError(describeAuthError(e)) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_AUTH) return
        try {
            onAuthorized(Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data))
        } catch (e: ApiException) {
            showError(describeAuthError(e))
        }
    }

    private fun onAuthorized(result: AuthorizationResult) {
        if (!GoogleAuth.hasAllScopes(result)) { showError(getString(R.string.err_scope)); return }
        val token = result.accessToken
        if (token.isNullOrEmpty()) { showError(getString(R.string.err_generic, "token")); return }
        syncText.visibility = View.VISIBLE
        syncText.setText(R.string.sync_loading)
        thread(name = "planner-connect") {
            val message = try {
                // 기본 캘린더 ID 가 곧 계정 이메일
                val email = GoogleApi.calendarList(token).firstOrNull { it.primary }?.id
                    ?: throw IllegalStateException("primary calendar")
                GoogleSync.onConnected(this, email)
                GoogleSync.sync(this, token)
                RefreshScheduler.ensure(this)
                null
            } catch (e: HttpStatusException) {
                getString(R.string.err_generic, "HTTP ${e.code}")
            } catch (e: java.io.IOException) {
                getString(R.string.err_network)
            } catch (e: Exception) {
                getString(R.string.err_generic, e.javaClass.simpleName)
            }
            runOnUiThread { if (!isFinishing) { showError(message); refreshUi() } }
        }
    }

    private fun describeAuthError(e: Exception): String {
        val code = (e as? ApiException)?.statusCode
        return when (code) {
            CommonStatusCodes.DEVELOPER_ERROR -> getString(R.string.err_developer)
            CommonStatusCodes.NETWORK_ERROR -> getString(R.string.err_network)
            CommonStatusCodes.CANCELED -> getString(R.string.err_canceled)
            CommonStatusCodes.INTERNAL_ERROR -> getString(R.string.err_internal) + detail(e)
            null -> getString(R.string.err_generic, e.javaClass.simpleName) + detail(e)
            else -> getString(R.string.err_generic, "코드 $code") + detail(e)
        }
    }

    /** Google 이 준 오류 설명 (원인 파악용) */
    private fun detail(e: Exception): String = e.message?.takeIf { it.isNotBlank() }?.let { "\n\n자세한 내용: $it" } ?: ""

    private fun confirmDisconnect() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.disconnect_confirm_title)
            .setMessage(R.string.disconnect_confirm_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.disconnect) { _, _ ->
                thread(name = "planner-disconnect") {
                    GoogleSync.disconnect(this)
                    runOnUiThread { showError(null); refreshUi() }
                }
            }
            .show()
    }

    private fun syncInBackground(showResult: Boolean) {
        if (syncing) return
        syncing = true
        thread(name = "planner-sync") {
            val error = GoogleSync.sync(this)
            syncing = false
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                refreshUi()
                if (showResult && Prefs.account(this) != null) {
                    toast(when (error) {
                        null -> getString(R.string.refreshed)
                        SyncError.NETWORK -> getString(R.string.refresh_offline)
                        SyncError.AUTH, SyncError.SCOPE -> getString(R.string.refresh_auth)
                        SyncError.OTHER -> getString(R.string.refresh_failed)
                    })
                }
            }
        }
    }

    // ── 화면 그리기 ──

    private fun refreshUi() {
        thread(name = "planner-ui") {
            val account = Prefs.account(this)
            val cache = CacheStore.load(this)?.takeIf { it.account == account }
            val status = Prefs.syncStatus(this)
            val snapshot = GoogleSync.snapshot(this)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                renderAccount(account, cache, status)
                renderCalendars(account, cache)
                renderPreview(snapshot)
            }
        }
    }

    private fun renderAccount(account: String?, cache: CalendarCache?, status: SyncStatus) {
        if (account == null) {
            accountText.setText(R.string.not_connected)
            syncText.visibility = View.GONE
            connectButton.visibility = View.VISIBLE
            connectButton.setText(R.string.connect_google)
            switchButton.visibility = View.GONE
            disconnectButton.visibility = View.GONE
            return
        }
        accountText.text = getString(R.string.connected_as, account)
        val time = cache?.let { WidgetUpdater.staleText(this, TodaySnapshot(WidgetState.NORMAL, emptyList(), emptyList(), 0f, staleSince = it.fetchedAt), short = true)?.removeSuffix(" 기준") }
        syncText.visibility = View.VISIBLE
        syncText.setTextColor(Palette.FAINT)
        when (status.lastError) {
            null -> syncText.text = if (time != null) getString(R.string.sync_ok, time) else getString(R.string.sync_loading)
            SyncError.NETWORK -> syncText.text = if (time != null) getString(R.string.sync_offline, time) else getString(R.string.sync_offline_none)
            SyncError.AUTH -> { syncText.setText(R.string.sync_auth); syncText.setTextColor(Palette.CORAL) }
            SyncError.SCOPE -> { syncText.setText(R.string.sync_scope); syncText.setTextColor(Palette.CORAL) }
            SyncError.OTHER -> { syncText.setText(R.string.sync_other); syncText.setTextColor(Palette.CORAL) }
        }
        val needsReconnect = status.lastError == SyncError.AUTH || status.lastError == SyncError.SCOPE
        connectButton.visibility = if (needsReconnect) View.VISIBLE else View.GONE
        connectButton.setText(R.string.reconnect_google)
        switchButton.visibility = View.VISIBLE
        disconnectButton.visibility = View.VISIBLE
    }

    private fun renderCalendars(account: String?, cache: CalendarCache?) {
        calendarList.removeAllViews()
        findViewById<View>(R.id.calendar_section).alpha = if (account != null) 1f else 0.45f
        if (account == null) { calendarHint.setText(R.string.calendar_hint_not_connected); return }
        if (cache == null) { calendarHint.setText(R.string.calendar_hint_loading); return }
        calendarHint.setText(R.string.calendar_hint)

        val calendars = cache.calendars.sortedWith(compareBy({ !it.primary }, { it.hidden || !it.selectedInGoogle }, { it.name }))
        val selected = Prefs.selectedCalendars(this, account) ?: DayBuilder.defaultSelection(cache.calendars)
        for (cal in calendars) {
            val label = SpannableStringBuilder(cal.name)
            val tag = when {
                cal.primary -> getString(R.string.primary_calendar)
                cal.hidden || !cal.selectedInGoogle -> getString(R.string.hidden_in_google)
                else -> null
            }
            if (tag != null) {
                val start = label.length
                label.append("\n").append(tag)
                label.setSpan(RelativeSizeSpan(0.8f), start, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                label.setSpan(ForegroundColorSpan(Palette.FAINT), start, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            calendarList.addView(CheckBox(this).apply {
                text = label
                textSize = 15f
                setTextColor(Palette.INK)
                isChecked = cal.id in selected
                buttonTintList = ColorStateList.valueOf(Palette.ACCENT)
                minHeight = dp(52)
                compoundDrawablePadding = dp(10)
                setPadding(paddingLeft, dp(6), paddingRight, dp(6))
                setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, swatch(cal.color), null)
                setOnCheckedChangeListener { _, checked ->
                    val current = (Prefs.selectedCalendars(this@MainActivity, account)
                        ?: DayBuilder.defaultSelection(cache.calendars)).toMutableSet()
                    if (checked) current += cal.id else current -= cal.id
                    Prefs.setSelectedCalendars(this@MainActivity, account, current)
                    applySelectionChange()
                }
            })
        }
    }

    /** 캘린더를 켜고 끄면: 모든 캘린더의 일정을 이미 받아 두었으므로 네트워크 없이 바로 2×2·4×4 다시 그림 */
    private fun applySelectionChange() {
        thread(name = "planner-selection") {
            WidgetUpdater.updateAll(this)
            val snapshot = GoogleSync.snapshot(this)
            runOnUiThread { if (!isFinishing) renderPreview(snapshot) }
        }
    }

    private fun swatch(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color or 0xFF000000.toInt())
        setSize(dp(14), dp(14))
    }

    /** 위젯 미리보기: 실제 위젯 RemoteViews 를 그대로 붙임 */
    private fun renderPreview(snapshot: TodaySnapshot) {
        val maxW = resources.displayMetrics.widthPixels - dp(40)
        val wDp = min(320, (maxW / density).roundToInt())
        val hDp = (wDp * 350f / 320f).roundToInt()
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, wDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, hDp)
        }
        previewBox.removeAllViews()
        val view = WidgetUpdater.buildViews(this, snapshot, options).apply(applicationContext, previewBox)
        previewBox.addView(view, FrameLayout.LayoutParams(dp(wDp), dp(hDp)).apply { gravity = Gravity.CENTER })

        val smallOptions = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 170)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 180)
        }
        previewSmallBox.removeAllViews()
        val small = WidgetUpdater.buildSmallViews(this, snapshot, smallOptions).apply(applicationContext, previewSmallBox)
        previewSmallBox.addView(small, FrameLayout.LayoutParams(dp(170), dp(180)).apply { gravity = Gravity.CENTER })
    }

    /** Google Cloud 의 Android OAuth 클라이언트 등록에 필요한 값 표시 (눌러서 SHA-1 복사) */
    private fun renderSigningInfo() {
        val sha1 = signingSha1()
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
        val view = findViewById<TextView>(R.id.signing_info)
        view.text = getString(R.string.app_info, packageName, sha1, version)
        view.setOnClickListener {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("SHA-1", sha1))
            toast(getString(R.string.copied))
        }
    }

    @Suppress("DEPRECATION")
    private fun signingSha1(): String = try {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
        } else {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
        }
        signatures?.firstOrNull()?.let { sig ->
            MessageDigest.getInstance("SHA-1").digest(sig.toByteArray()).joinToString(":") { "%02X".format(it) }
        } ?: "?"
    } catch (e: Exception) {
        "?"
    }

    private fun showError(message: String?) {
        errorText.visibility = if (message == null) View.GONE else View.VISIBLE
        errorText.text = message ?: ""
    }

    /** 홈 화면에 위젯 추가 (갤럭시 One UI 지원) */
    private fun pinWidget(provider: Class<out PlannerWidgetProvider>) {
        val manager = getSystemService(AppWidgetManager::class.java)
        if (manager.isRequestPinAppWidgetSupported) {
            manager.requestPinAppWidget(ComponentName(this, provider), null, null)
        } else toast(getString(R.string.pin_unsupported))
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * density).roundToInt()

    companion object {
        private const val REQUEST_AUTH = 42
    }
}

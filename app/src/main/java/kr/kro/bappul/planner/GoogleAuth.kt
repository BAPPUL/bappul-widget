package kr.kro.bappul.planner

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * Google 계정 연결 (Google Identity Services 의 AuthorizationClient).
 * - 클라이언트 ID 를 앱에 넣지 않음: Google Cloud 의 "Android" OAuth 클라이언트(패키지 이름 + 서명 SHA-1)로 앱을 알아봄.
 * - 읽기 권한만 요청: 캘린더 목록 읽기, 일정 읽기.
 * - 한 번 허용하면 이후에는 화면 없이(silent) 새 액세스 토큰을 받음 → 백그라운드 갱신 가능.
 */
object GoogleAuth {
    const val SCOPE_CALENDAR_LIST = "https://www.googleapis.com/auth/calendar.calendarlist.readonly"
    const val SCOPE_EVENTS = "https://www.googleapis.com/auth/calendar.events.readonly"
    val SCOPES = listOf(SCOPE_CALENDAR_LIST, SCOPE_EVENTS)

    fun request(account: String?): AuthorizationRequest {
        val builder = AuthorizationRequest.builder().setRequestedScopes(SCOPES.map { Scope(it) })
        if (account != null) builder.setAccount(Account(account, "com.google"))
        return builder.build()
    }

    fun hasAllScopes(result: AuthorizationResult): Boolean = result.grantedScopes.containsAll(SCOPES)

    sealed class TokenResult {
        data class Ok(val token: String) : TokenResult()
        /** 다시 동의가 필요함 (만료·권한 철회·일부 권한만 허용) → 앱에서 다시 연결 */
        data class NeedsUser(val scopeMissing: Boolean) : TokenResult()
        data class Failed(val network: Boolean, val error: Exception) : TokenResult()
    }

    /** 화면 없이 토큰 받기. 반드시 백그라운드 스레드에서 호출 (블로킹). */
    fun silentToken(context: Context, account: String): TokenResult = try {
        val result = Tasks.await(Identity.getAuthorizationClient(context).authorize(request(account)), 30, TimeUnit.SECONDS)
        val token = result.accessToken
        when {
            result.hasResolution() -> TokenResult.NeedsUser(scopeMissing = false)
            !hasAllScopes(result) -> TokenResult.NeedsUser(scopeMissing = true)
            token.isNullOrEmpty() -> TokenResult.NeedsUser(scopeMissing = false)
            else -> TokenResult.Ok(token)
        }
    } catch (e: ExecutionException) {
        val cause = e.cause
        val network = cause is ApiException && cause.statusCode == CommonStatusCodes.NETWORK_ERROR
        TokenResult.Failed(network, (cause as? Exception) ?: e)
    } catch (e: Exception) {
        TokenResult.Failed(network = e is java.util.concurrent.TimeoutException, error = e)
    }

    /** 401 을 받은 토큰을 Play 서비스 캐시에서 지움 (다음 요청에서 새 토큰을 받게) */
    fun clearToken(context: Context, token: String) {
        try { GoogleAuthUtil.clearToken(context, token) } catch (_: Exception) { }
    }
}

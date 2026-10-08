package com.vasanth.vaultnote.sync

import android.app.Activity
import android.content.Context
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import android.accounts.Account
import com.google.android.gms.auth.api.identity.RevokeAccessRequest

object DriveAuth {
    private val SCOPE = Scope("https://www.googleapis.com/auth/drive.appdata")

    fun request(): AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(listOf(SCOPE)).build()

    /** Access token without any UI. Null if the user must consent again. */
    suspend fun silentToken(ctx: Context): String? = suspendCancellableCoroutine { cont ->
        Identity.getAuthorizationClient(ctx).authorize(request())
            .addOnSuccessListener { r ->
                if (cont.isActive) cont.resume(if (r.hasResolution()) null else r.accessToken)
            }
            .addOnFailureListener { if (cont.isActive) cont.resume(null) }
    }

    /** Removes this app's Drive consent for the account, so the next sign-in shows the account picker. */
    suspend fun revoke(ctx: Context, email: String?): Boolean {
        if (email.isNullOrBlank()) return false
        val req = RevokeAccessRequest.builder()
            .setAccount(Account(email, "com.google"))
            .setScopes(listOf(SCOPE))
            .build()
        return suspendCancellableCoroutine { cont ->
            Identity.getAuthorizationClient(ctx).revokeAccess(req)
                .addOnSuccessListener { if (cont.isActive) cont.resume(true) }
                .addOnFailureListener { if (cont.isActive) cont.resume(false) }
        }
    }
}

/**
 * Interactive Google sign-in / consent for Drive. Create it as a property of the fragment:
 *   private val signIn = DriveSignIn(this) { token -> ... }   // token == null: cancelled or failed
 */
class DriveSignIn(private val fragment: Fragment, private val onResult: (String?) -> Unit) {

    private val launcher = fragment.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        if (res.resultCode != Activity.RESULT_OK || res.data == null) {
            onResult(null)
        } else {
            try {
                val r = Identity.getAuthorizationClient(fragment.requireContext())
                    .getAuthorizationResultFromIntent(res.data)
                onResult(r.accessToken)
            } catch (e: Exception) {
                onResult(null)
            }
        }
    }

    fun start() {
        Identity.getAuthorizationClient(fragment.requireContext()).authorize(DriveAuth.request())
            .addOnSuccessListener { r ->
                val pending = r.pendingIntent
                if (r.hasResolution() && pending != null) {
                    launcher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                } else {
                    onResult(r.accessToken)
                }
            }
            .addOnFailureListener { onResult(null) }
    }
}
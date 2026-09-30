package dev.nexa.biometrics

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** A user-triggered system BiometricPrompt scoped to this component. */
@Composable
public fun BiometricButtonImpl(
    title: String,
    reason: String,
    onAuthenticated: (() -> Unit)? = null,
    onFailed: ((BiometricFailure) -> Unit)? = null,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val latestOnAuthenticated = rememberUpdatedState(onAuthenticated)
    val latestOnFailed = rememberUpdatedState(onFailed)
    var cancellationSignal by remember { mutableStateOf<CancellationSignal?>(null) }
    var isAuthenticating by remember { mutableStateOf(false) }
    var isAttached by remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        onDispose {
            isAttached = false
            cancellationSignal?.cancel()
            cancellationSignal = null
        }
    }

    Button(
        enabled = !isAuthenticating,
        onClick = {
            val owner = activity
            if (owner == null || owner.isFinishing || owner.isDestroyed) {
                latestOnFailed.value?.invoke(BiometricFailure.invalidContext)
                return@Button
            }

            cancellationSignal?.cancel()
            val signal = CancellationSignal()
            cancellationSignal = signal
            isAuthenticating = true

            try {
                val prompt = BiometricPrompt.Builder(owner)
                    .setTitle("Authenticate")
                    .setSubtitle(reason)
                    .setNegativeButton("Cancel", owner.mainExecutor) { _, _ -> }
                    .build()
                prompt.authenticate(
                    signal,
                    owner.mainExecutor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(
                            result: BiometricPrompt.AuthenticationResult,
                        ) {
                            if (!isAttached) return
                            cancellationSignal = null
                            isAuthenticating = false
                            latestOnAuthenticated.value?.invoke()
                        }

                        override fun onAuthenticationFailed() {
                            // The system prompt keeps running to allow another attempt.
                        }

                        override fun onAuthenticationError(
                            errorCode: Int,
                            errString: CharSequence,
                        ) {
                            if (!isAttached) return
                            cancellationSignal = null
                            isAuthenticating = false
                            latestOnFailed.value?.invoke(errorFor(errorCode))
                        }
                    },
                )
            } catch (_: SecurityException) {
                cancellationSignal = null
                isAuthenticating = false
                latestOnFailed.value?.invoke(BiometricFailure.notAvailable)
            } catch (_: IllegalArgumentException) {
                cancellationSignal = null
                isAuthenticating = false
                latestOnFailed.value?.invoke(BiometricFailure.invalidContext)
            }
        },
    ) {
        Text(title)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> {
        val base = baseContext
        if (base === this) null else base.findActivity()
    }
    else -> null
}

private fun errorFor(code: Int): BiometricFailure = when (code) {
    BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT,
    BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE,
    -> BiometricFailure.notAvailable
    BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS -> BiometricFailure.notEnrolled
    BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT,
    BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT,
    -> BiometricFailure.lockout
    BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED -> BiometricFailure.userCanceled
    BiometricPrompt.BIOMETRIC_ERROR_CANCELED -> BiometricFailure.systemCanceled
    else -> BiometricFailure.unknown
}

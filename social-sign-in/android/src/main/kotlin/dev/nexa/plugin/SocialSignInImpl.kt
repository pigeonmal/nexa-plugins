package dev.nexa.plugin

import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import dev.nexa.core.NexaRuntimeCore

public class SocialSignInImpl(
    private val googleServerClientID: String,
    @Suppress("unused") private val googleIOSClientID: String?,
) : SocialSignInSpec {
    private val applicationContext = NexaRuntimeCore.context().applicationContext
    private val credentialManager = CredentialManager.create(applicationContext)

    override suspend fun signIn(provider: SocialProvider): SocialCredential = when (provider) {
        SocialProvider.google -> signInWithGoogle()
        SocialProvider.apple -> throw SocialSignInError.providerUnavailable
    }

    private suspend fun signInWithGoogle(): SocialCredential {
        if (googleServerClientID.isBlank()) throw SocialSignInError.appClientNotConfigured
        val activity = NexaRuntimeCore.currentActivity()
            ?.takeIf { !it.isFinishing && !it.isDestroyed }
            ?: throw SocialSignInError.presentationUnavailable
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(googleServerClientID)
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(true)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()

        val response = try {
            credentialManager.getCredential(activity, request)
        } catch (_: GetCredentialException) {
            throw SocialSignInError.signInFailed
        }
        val credential = response.credential as? CustomCredential
            ?: throw SocialSignInError.invalidCredential
        if (credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw SocialSignInError.invalidCredential
        }
        val googleCredential = try {
            GoogleIdTokenCredential.createFrom(credential.data)
        } catch (_: Exception) {
            throw SocialSignInError.invalidCredential
        }
        val idToken = googleCredential.idToken
        if (idToken.isBlank()) throw SocialSignInError.invalidCredential
        return SocialCredential(
            provider = SocialProvider.google,
            idToken = idToken,
            authorizationCode = "",
            nonce = "",
            email = googleCredential.email.orEmpty(),
            displayName = googleCredential.displayName.orEmpty(),
            photoURL = googleCredential.profilePictureUri?.toString().orEmpty(),
        )
    }

    override suspend fun signOut(provider: SocialProvider) {
        if (provider == SocialProvider.google) {
            runCatching { credentialManager.clearCredentialState(ClearCredentialStateRequest()) }
        }
    }
}

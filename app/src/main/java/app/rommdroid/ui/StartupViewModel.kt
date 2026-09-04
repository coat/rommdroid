package app.rommdroid.ui

import androidx.lifecycle.ViewModel
import app.rommdroid.data.repository.CredentialRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Thin ViewModel hoisted above the NavHost so the startup route decision
 * survives configuration changes.
 *
 * [CredentialRepository.isConfigured] is a synchronous property backed by
 * EncryptedSharedPreferences, so reading it in ViewModel init is safe and fast.
 * Holding the result in the ViewModel (rather than reading it in the Composable)
 * means a screen rotation won't flip the start destination.
 */
@HiltViewModel
class StartupViewModel @Inject constructor(
    credentials: CredentialRepository,
) : ViewModel() {

    /**
     * True when the app has a server URL and either an API token or Basic Auth
     * credentials — i.e., setup was previously completed.
     */
    val isConfigured: Boolean = credentials.isConfigured
}

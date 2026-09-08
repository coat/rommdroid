package app.rommdroid.ui

import androidx.lifecycle.ViewModel
import app.rommdroid.data.repository.CredentialRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Hoisted above the NavHost so the start-destination decision survives a
 *  rotation. [CredentialRepository.isConfigured] is synchronous and cheap. */
@HiltViewModel
class StartupViewModel @Inject constructor(
    credentials: CredentialRepository,
) : ViewModel() {

    /** True once setup has been completed. */
    val isConfigured: Boolean = credentials.isConfigured
}

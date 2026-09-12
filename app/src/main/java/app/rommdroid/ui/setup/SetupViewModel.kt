package app.rommdroid.ui.setup

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rommdroid.data.repository.ServerConnector
import app.rommdroid.ui.common.ConnectionState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val connector: ServerConnector,
) : ViewModel() {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    fun connect(serverUrl: String, username: String, password: String) {
        viewModelScope.launch {
            _state.value = ConnectionState.Loading
            _state.value = connector.signIn(serverUrl, username, password).fold(
                onSuccess = { ConnectionState.Saved },
                onFailure = { ConnectionState.Error(it.message ?: "Connection failed") },
            )
        }
    }
}

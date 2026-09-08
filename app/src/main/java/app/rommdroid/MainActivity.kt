package app.rommdroid

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import app.rommdroid.data.repository.GamepadLayoutRepository
import app.rommdroid.ui.RomMDroidNavHost
import app.rommdroid.ui.components.GamepadAction
import app.rommdroid.ui.components.GamepadDispatcher
import app.rommdroid.ui.components.LocalGamepad
import app.rommdroid.ui.components.LocalGamepadLayout
import app.rommdroid.ui.theme.RomMDroidTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** Which lettering the hint bars print. Injected because it is provided
     *  above the nav host, for every screen at once. */
    @Inject lateinit var buttonLayout: GamepadLayoutRepository

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    /** The controller map. Read at the Activity, above focus, because a focused
     *  native EditText swallows the whole key dispatch. */
    private val gamepad = GamepadDispatcher()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()

        // The last resort for B: whatever the system back gesture would do. It
        // routes through the same OnBackPressedDispatcher a Compose BackHandler
        // uses, so a screen handles B without knowing the button exists.
        gamepad.register { action ->
            if (action == GamepadAction.Back) {
                onBackPressedDispatcher.onBackPressed()
                true
            } else {
                false
            }
        }

        setContent {
            val layout by buttonLayout.layout.collectAsState()
            RomMDroidTheme {
                CompositionLocalProvider(
                    LocalGamepad       provides gamepad,
                    LocalGamepadLayout provides layout,
                ) {
                    RomMDroidNavHost()
                }
            }
        }
    }

    /**
     * A presses whatever holds focus; everything else goes to the screen.
     * Compose treats only DPAD_CENTER and Enter as a click, so A is rewritten
     * into one. As a key event rather than a "click the focused thing" call, so
     * buttons, rows and dialogs all work the one way.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_A) {
            return super.dispatchKeyEvent(event.rewrittenAs(KeyEvent.KEYCODE_DPAD_CENTER))
        }
        if (gamepad.onKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    /** Sticks and analog triggers. Read, never consumed: the framework
     *  synthesises the left stick's D-pad keys only for unhandled events. */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        gamepad.onMotionEvent(event)
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onPause() {
        super.onPause()
        // A held stick's release goes to whatever replaced us in the foreground.
        gamepad.release()
    }

    /** POST_NOTIFICATIONS is a runtime permission on Android 13+; without it
     *  download progress notifications are silently dropped. */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

/** The same press under a different keycode, device and timing intact. */
private fun KeyEvent.rewrittenAs(newKeyCode: Int) = KeyEvent(
    downTime, eventTime, action, newKeyCode, repeatCount, metaState,
    deviceId, scanCode, flags, source,
)

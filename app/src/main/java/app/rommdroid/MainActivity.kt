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
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import app.rommdroid.ui.RomMDroidNavHost
import app.rommdroid.ui.components.GamepadAction
import app.rommdroid.ui.components.GamepadDispatcher
import app.rommdroid.ui.components.LocalGamepad
import app.rommdroid.ui.theme.RomMDroidTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    /**
     * The controller map, read here rather than in the composition.
     *
     * A key event goes to whatever holds focus, and this app puts native
     * EditTexts inside its composition — once one is focused it swallows the
     * whole dispatch, and a `Modifier.onKeyEvent` in the tree above it never
     * runs.  The Activity sees every key before any of that, so the buttons are
     * read here and handed to the screen that registered for them.
     */
    private val gamepad = GamepadDispatcher()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()

        // The last resort for B, under every screen's own bindings: whatever
        // the system back gesture would have done.  That routes through the
        // same OnBackPressedDispatcher a Compose BackHandler registers with, so
        // a screen that closes its filter on back closes it on B too, without
        // knowing the button exists.
        gamepad.register { action ->
            if (action == GamepadAction.Back) {
                onBackPressedDispatcher.onBackPressed()
                true
            } else {
                false
            }
        }

        setContent {
            RomMDroidTheme {
                CompositionLocalProvider(LocalGamepad provides gamepad) {
                    RomMDroidNavHost()
                }
            }
        }
    }

    /**
     * A presses whatever holds focus, everything else goes to the screen.
     *
     * Compose treats only DPAD_CENTER and Enter as a click, so A — which
     * arrives as its own keycode and means nothing to anyone — is rewritten
     * into the press the focused row is already listening for.  Doing it as a
     * key event rather than as a "click the focused thing" call keeps buttons,
     * list rows and dialogs all working the one way.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_A) {
            return super.dispatchKeyEvent(event.rewrittenAs(KeyEvent.KEYCODE_DPAD_CENTER))
        }
        if (gamepad.onKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    /**
     * Sticks and analog triggers.
     *
     * Read, never consumed: the framework turns left-stick movement into D-pad
     * keys only for motion events that nothing handled, and that synthesis is
     * what makes the stick move focus at all.
     */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        gamepad.onMotionEvent(event)
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onPause() {
        super.onPause()
        // The release of a stick held as the app leaves the foreground goes to
        // whatever replaced it, so let go of everything here instead.
        gamepad.release()
    }

    /**
     * POST_NOTIFICATIONS is a runtime permission on Android 13+. It was declared
     * in the manifest but never requested, so download progress notifications
     * were silently dropped.
     */
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

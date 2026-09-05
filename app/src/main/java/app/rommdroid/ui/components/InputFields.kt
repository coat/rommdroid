package app.rommdroid.ui.components

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch

/*
 * These fields wrap a real android.widget.EditText in the stock Material3
 * outlined chrome, rather than using Compose's own text fields.
 *
 * Compose does not use native text controls: BasicTextField lays out and draws
 * the text itself and re-implements the IME protocol, and both of its
 * implementations get that wrong in ways this app trips over on a landscape
 * handheld:
 *
 *  - The old `OutlinedTextField(value, onValueChange)` keeps the text outside
 *    the field, so every keystroke round-trips through recomposition before it
 *    reaches the buffer the IME reads.  It renders a frame behind: backspaced
 *    characters linger, the password mask lands late.
 *  - The newer state-based fields (`TextFieldState`) fix that, but their
 *    InputConnection answers getExtractedText once and never calls
 *    updateExtractedText.  In landscape Gboard runs its full-screen extract
 *    editor — the field you actually type into is then the keyboard's mirror of
 *    ours — and that mirror is never refreshed, so typing shows nothing.  This
 *    is still true in Compose foundation 1.12.
 *
 * TextView implements the whole IME contract natively, including extracted-text
 * updates and password masking, so the extract editor mirrors it correctly and
 * so does the field itself.  When Compose's Material3 gains a text field without
 * these problems this can go back to being a few lines of Compose.
 */

enum class InputKind { Text, Uri, Password }

/**
 * In landscape the IME runs a full-screen editor that covers the app, so nothing
 * on screen says which field is being edited.  Two native levers exist and both
 * are set here; on a Retroid Pocket Nova (Gboard) neither is honoured, so on
 * that device the full-screen editor stays unlabelled.  Nothing else the editor
 * draws is ours except the action button, and naming a field there reads as if
 * the button sets that field rather than moving to it, so it stays generic:
 *
 *  - [EditorInfo.hintText] is the field's name, which the IME is meant to show
 *    as the hint of its extracted editor.  TextView fills it in from the view's
 *    hint, which we don't want drawn in the app (Material3 already draws a
 *    label), so set it on the EditorInfo directly.  `dumpsys input_method`
 *    confirms it arrives — `hintText=Username` — and Gboard draws nothing.
 *  - [EditorInfo.IME_FLAG_NO_FULLSCREEN] asks the IME not to take over the
 *    screen at all.  Gboard ignores it here too, full-screen either way.
 */
private class LabelledEditText(context: Context) : EditText(context) {
    var imeLabel: CharSequence? = null

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs)
        imeLabel?.let { outAttrs.hintText = it }
        outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_FULLSCREEN
        return connection
    }
}

/**
 * Handle for moving focus between fields — pass one to [OutlinedInputField] and
 * call [requestFocus] from the previous field's `onImeAction`.
 */
@Stable
class InputFieldHandle {
    internal var view: EditText? = null

    fun requestFocus() {
        val target = view ?: return
        target.requestFocus()
        val imm = target.context
            .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(target, 0)
    }
}

@Composable
fun rememberInputFieldHandle(): InputFieldHandle = remember { InputFieldHandle() }

private fun InputKind.androidInputType(): Int = when (this) {
    InputKind.Text     -> InputType.TYPE_CLASS_TEXT
    InputKind.Uri      -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    InputKind.Password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
}

@Composable
fun OutlinedInputField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    enabled: Boolean = true,
    isError: Boolean = false,
    inputKind: InputKind = InputKind.Text,
    imeAction: Int = EditorInfo.IME_ACTION_NEXT,
    imeLabel: String? = label ?: placeholder,
    handle: InputFieldHandle? = null,
    onImeAction: (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val colors = OutlinedTextFieldDefaults.colors()
    val scope  = rememberCoroutineScope()

    // The EditText owns the text; onValueChange only mirrors it outwards.  Keep
    // the latest lambda so the TextWatcher installed once in factory{} does not
    // capture a stale one.
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnImeAction   by rememberUpdatedState(onImeAction)
    val currentImeAction     by rememberUpdatedState(imeAction)

    val textColor   = MaterialTheme.colorScheme.onSurface.toArgb()
    val cursorColor = MaterialTheme.colorScheme.primary.toArgb()
    val textSizeSp  = MaterialTheme.typography.bodyLarge.fontSize.value

    var editText by remember { mutableStateOf<EditText?>(null) }
    val cursorDrawable = remember(cursorColor) { ColorDrawable(cursorColor) }

    Box(
        modifier = modifier.clickable(
            interactionSource = interactionSource,
            indication        = null,
            enabled           = enabled,
        ) {
            // Taps on the padding and label area should focus the field too —
            // the EditText only covers the inner row.
            editText?.let { view ->
                view.requestFocus()
                val imm = view.context
                    .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(view, 0)
            }
        }
    ) {
        OutlinedTextFieldDefaults.DecorationBox(
            value                = value,
            enabled              = enabled,
            singleLine           = true,
            visualTransformation = VisualTransformation.None,
            interactionSource    = interactionSource,
            isError              = isError,
            label                = label?.let { { Text(it) } },
            placeholder          = placeholder?.let { { Text(it) } },
            supportingText       = supportingText,
            colors               = colors,
            container = {
                OutlinedTextFieldDefaults.Container(
                    enabled           = enabled,
                    isError           = isError,
                    interactionSource = interactionSource,
                    colors            = colors,
                )
            },
            innerTextField = {
                AndroidView(
                    modifier = Modifier.fillMaxWidth(),
                    onRelease = { handle?.view = null },
                    factory  = { ctx ->
                        LabelledEditText(ctx).apply {
                            background = null
                            setPadding(0, 0, 0, 0)
                            gravity = Gravity.CENTER_VERTICAL
                            includeFontPadding = false
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            )

                            addTextChangedListener(object : TextWatcher {
                                override fun beforeTextChanged(
                                    s: CharSequence?, start: Int, count: Int, after: Int,
                                ) = Unit

                                override fun onTextChanged(
                                    s: CharSequence?, start: Int, before: Int, count: Int,
                                ) = Unit

                                override fun afterTextChanged(s: Editable?) {
                                    currentOnValueChange(s?.toString().orEmpty())
                                }
                            })

                            // The keyboard's action key reports the action id.
                            setOnEditorActionListener { _, actionId, _ ->
                                val handler = currentOnImeAction
                                if (handler != null && actionId == currentImeAction) {
                                    handler()
                                    true
                                } else {
                                    false
                                }
                            }

                            // A plain Enter — hardware keyboard, or a keyboard
                            // whose action key sends a key event — never reaches
                            // the action listener with our action id.  Take it
                            // here instead, consuming the down so TextView does
                            // not also advance focus, and acting on the up so the
                            // stray up cannot land on the field we moved to.
                            setOnKeyListener { _, keyCode, event ->
                                val handler = currentOnImeAction
                                val isEnter = keyCode == KeyEvent.KEYCODE_ENTER ||
                                    keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                                if (handler != null && isEnter && !event.isShiftPressed) {
                                    if (event.action == KeyEvent.ACTION_UP) handler()
                                    true
                                } else {
                                    false
                                }
                            }

                            // Feed focus into the Material chrome so the border
                            // and floating label react the way they normally do.
                            var focus: FocusInteraction.Focus? = null
                            setOnFocusChangeListener { _, hasFocus ->
                                scope.launch {
                                    if (hasFocus) {
                                        FocusInteraction.Focus().also {
                                            focus = it
                                            interactionSource.emit(it)
                                        }
                                    } else {
                                        focus?.let {
                                            interactionSource.emit(FocusInteraction.Unfocus(it))
                                        }
                                        focus = null
                                    }
                                }
                            }
                        }.also {
                            editText = it
                            handle?.view = it
                        }
                    },
                    update = { view ->
                        view.imeLabel = imeLabel

                        val wantedInputType = inputKind.androidInputType()
                        if (view.inputType != wantedInputType) {
                            view.inputType = wantedInputType
                            view.maxLines = 1
                            view.setHorizontallyScrolling(true)
                            // Password input types default the view to monospace.
                            view.typeface = Typeface.DEFAULT
                        }
                        if (view.imeOptions != imeAction) view.imeOptions = imeAction
                        if (view.isEnabled != enabled) view.isEnabled = enabled

                        view.setTextColor(textColor)
                        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
                        if (view.textCursorDrawable !== cursorDrawable) {
                            view.textCursorDrawable = cursorDrawable
                        }

                        // Only push text in when it changed underneath us —
                        // rewriting it on every recomposition would fight the
                        // user's cursor.
                        if (view.text.toString() != value) {
                            view.setText(value)
                            view.setSelection(value.length)
                        }
                    },
                )
            },
        )
    }
}

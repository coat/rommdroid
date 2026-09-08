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
import android.view.View
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
 * A real android.widget.EditText in stock Material3 outlined chrome, because
 * Compose re-implements the IME protocol and both implementations break on a
 * landscape handheld:
 *
 *  - `OutlinedTextField(value, onValueChange)` keeps the text outside the field,
 *    so every keystroke round-trips through recomposition and renders a frame
 *    behind: backspaced characters linger, the password mask lands late.
 *  - The `TextFieldState` fields answer getExtractedText once and never call
 *    updateExtractedText, so landscape Gboard's full-screen extract editor -
 *    the field you actually type into - never refreshes. Still true in
 *    foundation 1.12.
 *
 * TextView implements the contract natively. This can go back to a few lines of
 * Compose once Material3 has a text field without these problems.
 */

enum class InputKind { Text, Uri, Password }

/**
 * In landscape the IME's full-screen editor covers the app, so nothing says
 * which field is being edited. Both native levers are set here and Gboard
 * honours neither, so on a Retroid Pocket Nova the editor stays unlabelled:
 *
 *  - [EditorInfo.hintText] is set directly rather than through the view's hint,
 *    which Material3 already draws. `dumpsys input_method` confirms it arrives.
 *  - [EditorInfo.IME_FLAG_NO_FULLSCREEN] asks the IME not to take the screen.
 */
private class LabelledEditText(context: Context) : EditText(context) {
    var imeLabel: CharSequence? = null

    /** True only while [onCreateInputConnection] is inside its super call. */
    private var startingInput = false

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        startingInput = true
        val connection = try {
            super.onCreateInputConnection(outAttrs)
        } finally {
            startingInput = false
        }
        imeLabel?.let { outAttrs.hintText = it }
        outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_FULLSCREEN
        return connection
    }

    /**
     * Refuse to look for the next focusable view while the input connection is
     * being built. TextView asks twice, to set the IME's navigate flags, and a
     * LazyColumn answers by composing past its viewport hunting for a focusable
     * View it will never find among Compose nodes - laying out a few thousand
     * rows on the main thread and ANRing as the keyboard opens.
     *
     * Nothing wants the answer: fields hand off through
     * [InputFieldHandle.requestFocus]. Ordinary traversal still goes through.
     */
    override fun focusSearch(direction: Int): View? =
        if (startingInput) null else super.focusSearch(direction)
}

/** Handle for driving a field from outside: [requestFocus] from the previous
 *  field's `onImeAction` walks a form, [hideKeyboard] ends the editing. */
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

    /** Close the keyboard, leaving the field focused. TextView does this itself
     *  for [EditorInfo.IME_ACTION_DONE] only, so Search and Go have to ask. */
    fun hideKeyboard() {
        val target = view ?: return
        val imm = target.context
            .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(target.windowToken, 0)
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

    // The EditText owns the text; onValueChange only mirrors it outwards. Held
    // fresh so the TextWatcher installed once in factory{} sees the latest.
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
            // The EditText covers only the inner row; taps on the padding and
            // label should focus it too.
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

                            // A plain Enter never reaches the action listener
                            // with our action id. Consume the down so TextView
                            // does not also advance focus, and act on the up so
                            // the stray up misses the field we moved to.
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
                            // and floating label react normally.
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

                        // Only push text in when it changed underneath us, or
                        // the rewrite fights the user's cursor.
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

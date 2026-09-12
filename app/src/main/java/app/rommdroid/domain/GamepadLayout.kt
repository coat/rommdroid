package app.rommdroid.domain

/**
 * Which of the two face-button letterings the hints print. The positions are
 * fixed; only the letters move. Android's keycodes name them the Xbox way (A
 * bottom, B right), a Nintendo pad prints the other pairing on the same spots.
 *
 * Asked rather than detected: handhelds ship a "controller style" switch that
 * changes which keycode each position sends, both styles send the same ten
 * keycodes, and nothing in the API reports the setting.
 * `InputDevice.getKeyCodeForKeyLocation` is defined against a reference QWERTY
 * keyboard and knows nothing about gamepads.
 */
enum class GamepadLayout { Xbox, Nintendo }

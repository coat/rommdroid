package app.rommdroid.util

fun Long.formatSize(): String = when {
    this < 1_024L                  -> "$this B"
    this < 1_048_576L              -> "%.1f KB".format(this / 1_024.0)
    this < 1_073_741_824L          -> "%.1f MB".format(this / 1_048_576.0)
    else                           -> "%.2f GB".format(this / 1_073_741_824.0)
}

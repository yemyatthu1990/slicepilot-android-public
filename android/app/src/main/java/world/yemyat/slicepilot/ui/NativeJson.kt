package world.yemyat.slicepilot.ui

/**
 * The native library answers `nativeGetPrinters` / `nativeGetCompatibleProcesses` /
 * `nativeGetCompatibleFilaments` with JSON string arrays; this reads them.
 *
 * It gets its own file because it serves the whole `ui` package - `AppViewModel` calls it in five
 * places, Print setup renders its lists, and `app/src/test/.../ui/ConnectedPrinterTest` tests it -
 * while it used to sit at the bottom of `ConfigureSheet.kt`, where every one of those callers
 * depended on a file that was otherwise a single bottom sheet. Moving it out is what let that
 * sheet be deleted once its settings moved to [PrintSetupScreen].
 */
fun parseJsonStringArray(s: String): List<String> {
    val out = mutableListOf<String>()
    var i = 0
    while (i < s.length && s[i] != '[') i++
    i++
    val cur = StringBuilder()
    var inStr = false
    var esc = false
    while (i < s.length) {
        val c = s[i]
        if (inStr) {
            when {
                esc -> {
                    cur.append(unescapeChar(c))
                    esc = false
                }

                c == '\\' -> {
                    esc = true
                }

                c == '"' -> {
                    out.add(cur.toString())
                    cur.setLength(0)
                    inStr = false
                }

                else -> {
                    cur.append(c)
                }
            }
        } else {
            if (c == '"') {
                inStr = true
            } else if (c == ']') {
                break
            }
        }
        i++
    }
    return out
}

private fun unescapeChar(c: Char): Char =
    when (c) {
        'n' -> '\n'
        'r' -> '\r'
        't' -> '\t'
        'b' -> '\b'
        'f' -> '\u000c'
        '"' -> '"'
        '\\' -> '\\'
        else -> c
    }

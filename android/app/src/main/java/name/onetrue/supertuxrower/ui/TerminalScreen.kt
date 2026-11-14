package name.onetrue.supertuxrower.ui

import android.graphics.Canvas
import android.graphics.Typeface
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TextStyle
import com.termux.view.TerminalRenderer

/**
 * A real VT100 screen for Bob's terminal: Termux's emulator keeps the character grid, and its
 * renderer draws it. There's no process behind it; we feed it bytes ourselves.
 */
class TerminalScreen(textSizePx: Int, private val background: Int, private val cursor: Int) {
    private val renderer = TerminalRenderer(textSizePx, Typeface.MONOSPACE)
    private var emulator: TerminalEmulator? = null
    private val pending = StringBuilder()

    /** Sizes the grid to fit [width]×[height] pixels; returns the number of columns. */
    fun fit(width: Int, height: Int): Int {
        val cellWidth = renderer.fontWidth
        val cellHeight = renderer.fontLineSpacing
        val columns = (width / cellWidth).toInt().coerceAtLeast(10)
        val rows = (height / cellHeight).coerceAtLeast(4)
        val e = emulator
        if (e == null) {
            emulator = TerminalEmulator(Silent, columns, rows, cellWidth.toInt(), cellHeight, 2000, Quiet).also {
                it.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND] = background
                it.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] = cursor
                it.setCursorBlinkingEnabled(true)
            }
            feed("")
        } else if (e.mColumns != columns || e.mRows != rows) {
            e.resize(columns, rows, cellWidth.toInt(), cellHeight)
        }
        return columns
    }

    fun feed(text: String) {
        pending.append(text)
        val e = emulator ?: return
        if (pending.isEmpty()) return
        val bytes = pending.toString().toByteArray()
        pending.clear()
        e.append(bytes, bytes.size)
    }

    fun draw(canvas: Canvas, cursorOn: Boolean) {
        val e = emulator ?: return
        e.setCursorBlinkState(cursorOn)
        renderer.render(e, canvas, 0, -1, -1, -1, -1)
    }

    private object Silent : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) {}
        override fun titleChanged(oldTitle: String?, newTitle: String?) {}
        override fun onCopyTextToClipboard(text: String?) {}
        override fun onPasteTextFromClipboard() {}
        override fun onBell() {}
        override fun onColorsChanged() {}
    }

    private object Quiet : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {}
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {}
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
        override fun onPasteTextFromClipboard(session: TerminalSession?) {}
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int = TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK
        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }
}

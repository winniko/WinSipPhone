package it.winsipphone.tester.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import it.winsipphone.tester.R
import it.winsipphone.tester.contacts.Contacts
import it.winsipphone.tester.history.CallLog
import it.winsipphone.tester.sip.SipManager

/** Tastierino: numero/indirizzo, tasti 0-9 * #, chiamata audio e video. */
class DialpadTab(private val act: MainActivity) {
    private val number: EditText
    private val hint: TextView
    val view: View

    init {
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(act.dp(16), act.dp(12), act.dp(16), act.dp(16))
        }
        number = EditText(act).apply {
            textSize = 32f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            isSingleLine = true
            background = null
            this.hint = "Interno o IP"
            setHintTextColor(C.gray)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_URI
            // Il tastierino a schermo è quello dell'app; la tastiera di sistema si apre col pulsante "abc"
            showSoftInputOnFocus = false
        }
        col.addView(number, LinearLayout.LayoutParams(MATCH, WRAP))
        hint = act.label("", 14f, C.dim).apply { gravity = Gravity.CENTER; minHeight = act.dp(20) }
        col.addView(hint, LinearLayout.LayoutParams(MATCH, WRAP))
        number.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                hint.text = Contacts.find(s.toString())?.name.orEmpty()
            }
        })

        val keys = listOf(
            listOf("1" to "", "2" to "ABC", "3" to "DEF"),
            listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
            listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
            listOf("*" to "", "0" to "+", "#" to ""),
        )
        val grid = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        keys.forEach { row ->
            val r = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            row.forEach { (k, letters) -> r.addView(key(act, k, letters) { press(k[0]) }, LinearLayout.LayoutParams(0, act.dp(72), 1f)) }
            grid.addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        col.addView(grid, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = act.dp(8) })

        val actions = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        actions.addView(smallIcon(act, R.drawable.ic_videocam, C.button) { dial(true) }, LinearLayout.LayoutParams(0, act.dp(64), 1f))
        actions.addView(FrameLayout(act).apply {
            addView(circle(act, R.drawable.ic_call, C.green, 72) { dial(false) }, FrameLayout.LayoutParams(act.dp(72), act.dp(72), Gravity.CENTER))
        }, LinearLayout.LayoutParams(0, act.dp(80), 1f))
        val back = smallIcon(act, R.drawable.ic_backspace, C.button) {
            val t = number.text
            val start = number.selectionStart.coerceAtLeast(0)
            if (start > 0) t.delete(start - 1, start) else if (t.isNotEmpty() && start == 0 && number.selectionEnd == t.length) t.clear()
        }
        back.setOnLongClickListener { number.text.clear(); true }
        actions.addView(back, LinearLayout.LayoutParams(0, act.dp(64), 1f))
        col.addView(actions, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = act.dp(8) })

        col.addView(act.label("abc  —  tastiera per scrivere indirizzi IP o interno@IP", 13f, C.accent).apply {
            gravity = Gravity.CENTER
            setPadding(0, act.dp(10), 0, 0)
            setOnClickListener {
                number.requestFocus()
                (act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(number, InputMethodManager.SHOW_FORCED)
            }
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        view = col
    }

    private fun press(k: Char) {
        SipManager.playKeyTone(k)
        val start = number.selectionStart.coerceAtLeast(0)
        val end = number.selectionEnd.coerceAtLeast(0)
        number.text.replace(minOf(start, end), maxOf(start, end), k.toString())
    }

    fun setNumber(n: String) {
        number.setText(n)
        number.setSelection(number.text.length)
    }

    private fun dial(video: Boolean) {
        val n = number.text.toString().trim()
        if (n.isEmpty()) {
            // Come sui telefoni: tasto chiamata a vuoto = ultimo numero chiamato
            CallLog.list.value.firstOrNull { !it.incoming }?.let { setNumber(it.number) } ?: act.toast("Scrivi un numero")
            return
        }
        act.call(n, video)
    }

    companion object {
        fun key(ctx: Context, k: String, letters: String, onClick: () -> Unit): View {
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                background = ctx.getDrawable(android.R.drawable.list_selector_background)
                setOnClickListener { onClick() }
            }
            box.addView(ctx.label(k, 30f, Color.WHITE).apply { gravity = Gravity.CENTER })
            if (letters.isNotEmpty()) box.addView(ctx.label(letters, 11f, C.dim).apply { gravity = Gravity.CENTER })
            return box
        }

        fun circle(ctx: Context, icon: Int, color: Int, sizeDp: Int, onClick: () -> Unit): View =
            FrameLayout(ctx).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
                isClickable = true
                foreground = ctx.getDrawable(android.R.drawable.list_selector_background)
                addView(ImageView(ctx).apply { setImageResource(icon); setColorFilter(Color.WHITE) },
                    FrameLayout.LayoutParams(ctx.dp(sizeDp / 2), ctx.dp(sizeDp / 2), Gravity.CENTER))
                setOnClickListener { onClick() }
            }

        fun smallIcon(ctx: Context, icon: Int, color: Int, onClick: () -> Unit): View =
            FrameLayout(ctx).apply {
                isClickable = true
                background = ctx.getDrawable(android.R.drawable.list_selector_background)
                addView(circle(ctx, icon, color, 52, onClick), FrameLayout.LayoutParams(ctx.dp(52), ctx.dp(52), Gravity.CENTER))
            }
    }
}

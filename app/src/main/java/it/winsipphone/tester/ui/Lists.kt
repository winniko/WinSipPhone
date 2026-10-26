package it.winsipphone.tester.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.FileProvider
import java.io.File

/** Colori dell'app. */
object C {
    val bg = Color.rgb(17, 23, 29)
    val card = Color.rgb(27, 35, 44)
    val accent = Color.rgb(100, 181, 246)
    val green = Color.rgb(46, 160, 67)
    val red = Color.rgb(211, 47, 47)
    val orange = Color.rgb(245, 124, 0)
    val gray = Color.rgb(84, 98, 110)
    val dim = Color.rgb(160, 172, 182)
    val button = Color.rgb(44, 56, 68)
}

/** Adattatore minimale per ListView: [render] crea la riga. */
class SimpleAdapter<T>(private val render: (T) -> View) : BaseAdapter() {
    var items: List<T> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }

    override fun getCount() = items.size
    override fun getItem(position: Int): T = items[position]
    override fun getItemId(position: Int) = position.toLong()
    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View = render(items[position])
}

/** Riga di elenco: icona colorata, titolo, sottotitolo e testo a destra. */
fun Context.listRow(@DrawableRes icon: Int?, iconColor: Int, title: String, subtitle: String, right: String = ""): LinearLayout {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(10), dp(16), dp(10))
        minimumHeight = dp(64)
    }
    if (icon != null) {
        row.addView(ImageView(this).apply {
            setImageResource(icon)
            setColorFilter(iconColor)
        }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(16) })
    }
    val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    texts.addView(label(title, 17f, Color.WHITE).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
    if (subtitle.isNotEmpty()) texts.addView(label(subtitle, 14f, C.dim).apply { maxLines = 2 })
    row.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
    if (right.isNotEmpty()) row.addView(label(right, 13f, C.dim).apply { gravity = Gravity.END })
    return row
}

/** Pallino colorato (stato registrazione / BLF). */
fun Context.dot(color: Int, sizeDp: Int = 12): View = View(this).apply {
    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

fun View.setDotColor(color: Int) {
    (background as? GradientDrawable)?.setColor(color)
}

/** Condivide un testo come file .txt/.json (WhatsApp, email, Drive...). */
fun Context.shareFile(fileName: String, text: String, title: String) {
    val dir = File(cacheDir, "share").apply { mkdirs() }
    val f = File(dir, fileName)
    f.writeText(text)
    val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
    val i = Intent(Intent.ACTION_SEND)
        .setType(if (fileName.endsWith(".json")) "application/json" else "text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, title)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(i, title))
}

fun formatDuration(sec: Int): String = if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)

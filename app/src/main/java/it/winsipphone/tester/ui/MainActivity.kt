package it.winsipphone.tester.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomnavigation.BottomNavigationView
import it.winsipphone.tester.Bus
import it.winsipphone.tester.CallUi
import it.winsipphone.tester.R
import it.winsipphone.tester.config.ConfigRepository
import it.winsipphone.tester.service.CallNotifications
import it.winsipphone.tester.service.PhoneService
import it.winsipphone.tester.sip.SipManager
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_DIAL = 0
        const val TAB_CONTACTS = 1
        const val TAB_BLF = 2
        const val TAB_HISTORY = 3
        const val TAB_TOOLS = 4
        private const val REQ_PERMS = 7
        /** Chiedo batteria/schermo intero una sola volta per avvio dell'app. */
        private var askedThisRun = false
    }

    private lateinit var content: FrameLayout
    private lateinit var nav: BottomNavigationView
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var callBanner: TextView
    private val tabs = arrayOfNulls<View>(5)
    lateinit var dialpad: DialpadTab
        private set
    private var isResumedNow = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PhoneService.start(this)
        buildUi()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(Bus.registration, Bus.registered) { r, ok -> r to ok }.collect { (r, ok) ->
                        val acc = ConfigRepository.current.account
                        statusText.text = if (acc.enabled && acc.username.isNotBlank()) "${acc.username}: $r" else r
                        statusDot.setDotColor(when {
                            ok -> C.green
                            r.startsWith("errore") -> C.red
                            r.contains("corso") -> C.orange
                            else -> C.gray
                        })
                    }
                }
                launch {
                    Bus.call.collect { s ->
                        callBanner.show(s !is CallUi.Idle && s !is CallUi.Ended)
                        callBanner.text = when (s) {
                            is CallUi.Incoming -> "Chiamata in arrivo da ${s.name} — tocca per rispondere"
                            is CallUi.Outgoing -> "Chiamata verso ${s.name} — tocca per tornare"
                            is CallUi.Active -> "In chiamata con ${s.name} — tocca per tornare"
                            else -> ""
                        }
                        if (s is CallUi.Incoming && isResumedNow) openCall()
                    }
                }
            }
        }
        handleIntent(intent)
        if (!ConfigRepository.introDone) showIntro() else ensurePermissions()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.bg) }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(4), dp(8))
            setBackgroundColor(C.card)
        }
        statusDot = dot(C.gray)
        top.addView(statusDot)
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        titles.addView(label(getString(R.string.app_name), 18f, android.graphics.Color.WHITE, bold = true))
        statusText = label("", 13f, C.dim).apply { maxLines = 2 }
        titles.addView(statusText)
        top.addView(titles, LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_settings)
            setColorFilter(android.graphics.Color.WHITE)
            background = null
            contentDescription = "Impostazioni"
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        })
        root.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))

        callBanner = label("", 15f, android.graphics.Color.WHITE, bold = true).apply {
            setBackgroundColor(C.green)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            visibility = View.GONE
            setOnClickListener { openCall() }
        }
        root.addView(callBanner, LinearLayout.LayoutParams(MATCH, WRAP))

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))

        nav = BottomNavigationView(this).apply {
            setBackgroundColor(C.card)
            labelVisibilityMode = BottomNavigationView.LABEL_VISIBILITY_LABELED
            val tint = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(C.accent, C.dim))
            itemIconTintList = tint
            itemTextColor = tint
            menu.add(0, TAB_DIAL, 0, "Tastiera").setIcon(R.drawable.ic_dialpad)
            menu.add(0, TAB_CONTACTS, 1, "Rubrica").setIcon(R.drawable.ic_person)
            menu.add(0, TAB_BLF, 2, "Interni").setIcon(R.drawable.ic_group)
            menu.add(0, TAB_HISTORY, 3, "Storico").setIcon(R.drawable.ic_history)
            menu.add(0, TAB_TOOLS, 4, "Strumenti").setIcon(R.drawable.ic_build)
            setOnItemSelectedListener { showTab(it.itemId); true }
        }
        root.addView(nav, LinearLayout.LayoutParams(MATCH, WRAP))
        setContentView(root)

        dialpad = DialpadTab(this)
        tabs[TAB_DIAL] = dialpad.view
        showTab(TAB_DIAL)
    }

    fun selectTab(i: Int) {
        nav.selectedItemId = i
    }

    private fun showTab(i: Int) {
        val v = tabs[i] ?: when (i) {
            TAB_CONTACTS -> ContactsTab(this).view
            TAB_BLF -> BlfTab(this).view
            TAB_HISTORY -> HistoryTab(this).view
            TAB_TOOLS -> ToolsTab(this).view
            else -> dialpad.view
        }.also { tabs[i] = it }
        content.removeAllViews()
        content.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    @Deprecated("startActivityForResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        ImportHelper.handle(this, requestCode, resultCode, data)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        i ?: return
        if (i.hasExtra(EXTRA_TAB)) selectTab(i.getIntExtra(EXTRA_TAB, TAB_DIAL))
        val data = i.data
        if (data != null && (data.scheme == "sip" || data.scheme == "sips")) {
            selectTab(TAB_DIAL)
            dialpad.setNumber(data.schemeSpecificPart.orEmpty())
        }
    }

    override fun onResume() {
        super.onResume()
        isResumedNow = true
        if (Bus.call.value is CallUi.Incoming) openCall()
    }

    override fun onPause() {
        isResumedNow = false
        super.onPause()
    }

    fun openCall() {
        startActivity(Intent(this, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Avvia una chiamata dalla tastiera, rubrica, interni o storico. */
    fun call(number: String, video: Boolean) {
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            toast("Serve il permesso del microfono per telefonare")
            ensurePermissions()
            return
        }
        val err = SipManager.dial(number, video && (granted(Manifest.permission.CAMERA)))
        if (err != null) toast(err) else openCall()
    }

    // ------------------------------------------------------------------ primo avvio e permessi

    private fun showIntro() {
        AlertDialog.Builder(this)
            .setTitle("Benvenuto in WinSipPhone Tester")
            .setMessage("Telefono SIP per chiamate e videochiamate tramite centralino (es. FreePBX) " +
                "oppure dirette a un indirizzo IP, con strumenti per collaudare impianti e citofoni.\n\n" +
                "Ora l'app chiederà i permessi per microfono, fotocamera e notifiche, e di non essere " +
                "limitata dal risparmio batteria: servono per ricevere le chiamate anche a telefono bloccato.")
            .setCancelable(false)
            .setPositiveButton("Continua") { _, _ ->
                ConfigRepository.introDone = true
                ensurePermissions()
            }
            .show()
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    fun ensurePermissions() {
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= 31) need += Manifest.permission.BLUETOOTH_CONNECT
        val missing = need.filter { !granted(it) }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_PERMS) else checkSystemSettings()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) checkSystemSettings()
    }

    @SuppressLint("BatteryLife")
    private fun checkSystemSettings(force: Boolean = false) {
        if (askedThisRun && !force) return
        askedThisRun = true
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            AlertDialog.Builder(this)
                .setTitle("Risparmio batteria")
                .setMessage("Per ricevere le chiamate con lo schermo spento, consenti all'app di funzionare " +
                    "senza limiti di batteria. Nella prossima finestra scegli \"Consenti\".")
                .setPositiveButton("Ok") { _, _ ->
                    runCatching {
                        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                    }
                }
                .setNegativeButton("Più tardi") { _, _ -> checkFullScreen() }
                .show()
        } else {
            checkFullScreen()
        }
    }

    private fun checkFullScreen() {
        if (CallNotifications.canFullScreen(this)) return afterSetup()
        AlertDialog.Builder(this)
            .setTitle("Chiamate a schermo intero")
            .setMessage("Per mostrare la chiamata in arrivo anche a telefono bloccato, attiva " +
                "\"Notifiche a schermo intero\" per WinSipPhone Tester nella prossima schermata.")
            .setPositiveButton("Apri impostazioni") { _, _ ->
                runCatching {
                    startActivity(Intent("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT", Uri.parse("package:$packageName")))
                }
            }
            .setNegativeButton("Più tardi") { _, _ -> afterSetup() }
            .show()
    }

    private fun afterSetup() {
        val acc = ConfigRepository.current.account
        if (!acc.enabled && acc.server.isBlank()) {
            AlertDialog.Builder(this)
                .setTitle("Centralino")
                .setMessage("Vuoi configurare ora l'account del centralino (FreePBX)?\n\n" +
                    "Senza account puoi comunque chiamare direttamente un indirizzo IP, es. 192.168.1.50")
                .setPositiveButton("Configura") { _, _ -> startActivity(Intent(this, SettingsActivity::class.java)) }
                .setNegativeButton("Più tardi", null)
                .show()
        }
    }

    /** Dalla scheda Strumenti: ricontrolla permessi e impostazioni di sistema. */
    fun recheckPermissions() {
        askedThisRun = false
        ensurePermissions()
        if (listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA).all { granted(it) }) checkSystemSettings(force = true)
    }
}

package it.winsipphone.tester

import android.app.Application
import it.winsipphone.tester.config.ConfigRepository
import it.winsipphone.tester.contacts.Contacts
import it.winsipphone.tester.history.CallLog
import it.winsipphone.tester.media.Ringer
import it.winsipphone.tester.service.CallNotifications
import it.winsipphone.tester.sip.SipManager
import it.winsipphone.tester.util.L

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ConfigRepository.init(this)
        Contacts.init(this)
        CallLog.init(this)
        Ringer.init(this)
        SipManager.init(this)
        CallNotifications.createChannels(this)
        L.i("App", "WinSipPhone Tester ${BuildConfig.VERSION_NAME} avviata")
    }
}

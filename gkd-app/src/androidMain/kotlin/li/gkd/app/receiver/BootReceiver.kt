package li.gkd.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import li.gkd.app.appScope
import li.gkd.app.priv.RootKeepAlive
import li.gkd.app.service.StatusService
import li.gkd.app.service.fixRestartAutomatorService
import li.gkd.app.settings.SettingsRepository
import li.gkd.app.util.launchLogged

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            fixRestartAutomatorService()
            StatusService.autoStart()
            // 看门狗脚本在 /data/adb 下重启后依然存在，但 root 会话会断，需要重新拉起
            if (SettingsRepository.settings.value.enableRootKeepAlive) {
                appScope.launchLogged(Dispatchers.IO, loc = "@gkd/receiver/BootReceiver") {
                    RootKeepAlive.start()
                }
            }
        }
    }
}

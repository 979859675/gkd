package li.gkd.app.platform

import kotlinx.coroutines.Dispatchers
import li.gkd.app.appScope
import li.gkd.app.logging.LogUtils
import li.gkd.app.priv.RootKeepAlive
import li.gkd.app.service.CompanionServiceController
import li.gkd.app.service.StatusService
import li.gkd.app.service.fixRestartAutomatorService
import li.gkd.app.settings.SettingsRepository
import li.gkd.app.util.launchLogged

actual fun requestAutomatorRestart(): PlatformResult<Unit> {
    fixRestartAutomatorService()
    return PlatformResult.Success(Unit)
}

actual fun requestCompanionServiceUpdate(): PlatformResult<Unit> {
    CompanionServiceController.update()
    return PlatformResult.Success(Unit)
}

actual fun requestRootKeepAliveUpdate() {
    val settings = SettingsRepository.settings.value
    appScope.launchLogged(Dispatchers.IO, loc = "@gkd/platform/RootKeepAlive") {
        if (settings.enableRootKeepAlive) {
            RootKeepAlive.start()
        } else {
            RootKeepAlive.stop()
        }
        // 关闭常驻通知后立即停止前台服务，避免开关与状态不一致
        if (!settings.enableStatusService) {
            runCatching { StatusService.stop() }
                .onFailure { LogUtils.w("停止常驻通知失败", it) }
        }
    }
}

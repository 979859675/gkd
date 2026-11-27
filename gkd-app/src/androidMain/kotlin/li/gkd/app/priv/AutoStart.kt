package li.gkd.app.priv

import android.os.Handler
import android.os.Looper
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import li.gkd.app.app
import li.gkd.app.appScope
import li.gkd.app.logging.LogUtils
import li.gkd.app.permission.AndroidPermissions
import li.gkd.app.permission.PermissionStates
import li.gkd.app.resources.*
import li.gkd.app.service.A11yService
import li.gkd.app.service.fixRestartAutomatorService
import li.gkd.app.settings.SettingsRepository
import li.gkd.app.util.ToastUtils
import li.gkd.app.util.launchLogged
import priv.kit.core.Privilege

/**
 * 启动时自动拉起特权服务与无障碍服务，装好后不需要手动逐个开关。
 *
 * - 特权服务：检测到 Root 才用 Root 启动，失败不影响其他能力；
 * - 无障碍：特权服务连上后由它授予 WRITE_SECURE_SETTINGS，再开启首选无障碍服务；
 * - 总开关：自动打开「自动化」和「规则匹配」「常驻通知」。
 */
object AutoStart {
    private val A11Y_CHECK_DELAY = 5.seconds

    private var requested = false

    fun requestOnce() {
        if (requested) return
        requested = true
        appScope.launchLogged(Dispatchers.IO) {
            enableAutomatorIfNeeded()
            if (privilegeContextFlow.value == null) startRootPrivilege()
            awaitPrivilege()
            if (privilegeContextFlow.value != null) grantSecureSettings()
            PermissionStates.refreshAll()
            fixRestartAutomatorService("@gkd/app/priv/AutoStart")
            showRestrictedSettingsGuideIfNeeded()
        }
    }

    /**
     * 系统在“受限制设置”未解除时会静默忽略无障碍开关，只给用户提示，不改动系统设置。
     * 已开启 LSPosed 模块并勾选 GKD 后该提示不会出现。
     */
    private suspend fun showRestrictedSettingsGuideIfNeeded() {
        if (privilegeContextFlow.value != null) return
        val settings = SettingsRepository.settings.value
        if (!settings.enableAutomator || !settings.useA11y) return
        delay(A11Y_CHECK_DELAY)
        if (A11yService.isRunning.value) return
        Handler(Looper.getMainLooper()).post {
            ToastUtils.show(Res.string.a11y_restricted_settings_guide.getSync())
        }
    }

    private fun enableAutomatorIfNeeded() {
        val settings = SettingsRepository.settings.value
        if (settings.enableAutomator && settings.enableMatch && settings.enableStatusService) return
        SettingsRepository.updateSettings {
            it.copy(
                enableAutomator = true,
                enableMatch = true,
                enableStatusService = true,
            )
        }
        LogUtils.i("AutoStart: 已打开自动化、规则匹配与常驻通知")
    }

    private suspend fun startRootPrivilege() {
        if (!isRootAvailable()) {
            LogUtils.i("AutoStart: 未检测到 Root，跳过 Root 启动特权服务")
            return
        }
        try {
            Privilege.startRoot()
            LogUtils.i("AutoStart: Root 特权服务启动成功")
        } catch (e: Exception) {
            LogUtils.w("AutoStart: Root 特权服务启动失败", e)
        }
    }

    private suspend fun isRootAvailable() =
        withContext(Dispatchers.IO) {
            runCatching {
                    val process =
                        ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
                    val output =
                        process.inputStream.bufferedReader().use { it.readText() }
                    process.waitFor()
                    output.contains("uid=0")
                }
                .getOrDefault(false)
        }

    private suspend fun awaitPrivilege() {
        repeat(30) {
            if (privilegeContextFlow.value != null) return
            delay(1.seconds)
        }
    }

    private fun grantSecureSettings() {
        val permission = AndroidPermissions.WRITE_SECURE_SETTINGS
        if (app.checkGrantedPermission(permission)) return
        runCatching {
                Privilege.grantRuntimePermission(
                    packageName = app.packageName,
                    permissionName = permission,
                )
            }
            .onFailure { LogUtils.w("AutoStart: 授予 WRITE_SECURE_SETTINGS 失败", it) }
    }
}

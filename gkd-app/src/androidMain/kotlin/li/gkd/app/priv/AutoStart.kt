package li.gkd.app.priv

import android.os.Handler
import android.os.Looper
import com.google.android.accessibility.selecttospeak.SelectToSpeakServiceCompanion
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
import li.gkd.app.settings.AutomatorMode
import li.gkd.app.settings.SettingsRepository
import li.gkd.app.util.ToastUtils
import li.gkd.app.util.componentName
import li.gkd.app.util.launchLogged
import priv.kit.core.Privilege

/**
 * 启动时自动拉起特权服务，装好后不需要手动逐个开关。
 *
 * - 特权服务：检测到 Root 才用 Root 启动，失败不影响其他能力；
 * - 工作模式：默认「自动化」，由特权服务用 UiAutomation 读写界面，不占用系统无障碍开关；
 * - 清理：自动化模式下把 GKD 自身与伴生无障碍从系统无障碍列表移除；
 * - 总开关：自动打开「自动化」「规则匹配」「常驻通知」。
 */
object AutoStart {
    private val GUIDE_DELAY = 5.seconds

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
            disableA11yServicesIfAutomation()
            fixRestartAutomatorService("@gkd/app/priv/AutoStart")
            showPrivilegeGuideIfNeeded()
        }
    }

    /**
     * 自动化模式默认不启用任何无障碍服务：把 GKD 自身与伴生无障碍从系统无障碍列表移除，
     * 这样系统设置里看不到无障碍开启，应用也无法检测到 GKD 用了无障碍。
     * 无障碍模式（用户手动切回）不受影响。
     */
    private suspend fun disableA11yServicesIfAutomation() {
        val settings = SettingsRepository.settings.value
        if (!settings.useAutomation) return
        if (!PermissionStates.writeSecureSettings.updateAndGet()) return
        withContext(Dispatchers.IO) {
            runCatching {
                val names = app.getSecureA11yServices()
                val removed =
                    names.filterTo(mutableSetOf()) {
                        it == A11yService.standardComponent ||
                            it == A11yService.privilegedComponent ||
                            it == SelectToSpeakServiceCompanion::class.componentName
                    }
                if (removed.isEmpty()) return@runCatching
                if (!app.putSecureA11yServices(names - removed)) {
                    LogUtils.w("AutoStart: 移除 GKD 无障碍服务失败")
                    return@runCatching
                }
                LogUtils.i("AutoStart: 已从无障碍列表移除 ${removed.size} 个 GKD 服务")
            }
                .onFailure { LogUtils.w("AutoStart: 清理 GKD 无障碍服务失败", it) }
        }
    }

    /** 自动化模式不依赖无障碍，没有 Root 特权服务就点不动，必须提示用户。 */
    private suspend fun showPrivilegeGuideIfNeeded() {
        if (privilegeContextFlow.value != null) return
        val settings = SettingsRepository.settings.value
        if (!settings.enableAutomator || !settings.useAutomation) return
        delay(GUIDE_DELAY)
        Handler(Looper.getMainLooper()).post {
            ToastUtils.show(Res.string.root_privilege_required_guide.getSync())
        }
    }

    private fun enableAutomatorIfNeeded() {
        val settings = SettingsRepository.settings.value
        val firstRun = !settings.rootOnlyModeApplied
        if (
            !firstRun &&
                settings.enableAutomator &&
                settings.enableMatch &&
                settings.enableStatusService
        )
            return
        SettingsRepository.updateSettings {
            it.copy(
                enableAutomator = true,
                enableMatch = true,
                enableStatusService = true,
                // 只在本构建第一次启动时切到自动化，之后保留用户在「工作模式」里的选择
                automatorMode =
                    if (firstRun) AutomatorMode.Automation.value else it.automatorMode,
                rootOnlyModeApplied = true,
            )
        }
        LogUtils.i(
            "AutoStart: 已打开自动化、规则匹配与常驻通知" + if (firstRun) "（工作模式=自动化）" else ""
        )
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

package li.gkd.app.service

import android.content.Intent
import kotlinx.coroutines.Dispatchers
import li.gkd.app.appScope
import li.gkd.app.logging.LogUtils
import li.gkd.app.permission.PermissionStates
import li.gkd.app.priv.AutoStart
import li.gkd.app.priv.privilegeContextFlow
import li.gkd.app.priv.recoverPrivilegeContext
import li.gkd.app.util.launchLogged

/**
 * 被 root 看门狗拉起时使用的入口。
 *
 * 这是一个普通 service（没有 foregroundServiceType），所以启动它**不需要常驻通知**；
 * root 用 `am start-service` 调用时不受「后台禁止启动服务」限制。
 * 它只负责把运行时恢复起来：重连特权服务、刷新权限、重新拉起自动化。
 */
class RootWakeService : LifecycleHookService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        LogUtils.i("RootWakeService: 收到看门狗唤醒")
        appScope.launchLogged(Dispatchers.IO, loc = "@gkd/app/service/RootWakeService") {
            try {
                if (privilegeContextFlow.value == null) {
                    recoverPrivilegeContext()
                }
                PermissionStates.refreshAll()
                AutoStart.resumeAfterWake()
                fixRestartAutomatorService("@gkd/app/service/RootWakeService")
            } catch (e: Exception) {
                LogUtils.w("RootWakeService: 恢复运行时失败", e)
            } finally {
                stopSelf(startId)
            }
        }
        return START_STICKY
    }
}

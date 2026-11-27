package li.gkd.app.xposed

import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import li.gkd.app.priv.AutoStart
import li.gkd.app.service.startAutomatorFromHost

/**
 * GKD 的 LSPosed 模块入口（libxposed Modern API 102）。
 *
 * 作用范围只有 GKD 自身（`META-INF/xposed/scope.list` 只声明 `li.songe.gkd`）：
 *
 * 1. GKD 被 LSPosed 作用域包含后，系统将其视为已授权的模块应用，
 *    Android 13+ 的“受限制设置”不再阻止它写入 `Settings.Secure`，
 *    于是 Root / Shizuku / ADB 已授权时可以直接开启并保活自己的无障碍服务；
 * 2. 进程在 GKD `App.attachBaseContext` 之后立即请求一次自动化 + 无障碍自动启动，
 *    从最近任务、开机广播或外部入口拉起时都不需要再手动点开关。
 *
 * 未安装 LSPosed 时该入口不会被加载，普通安装方式的行为不变。
 */
class GkdXposedModule : XposedModule() {
    private var bootstrapped = false

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName != META_PACKAGE || bootstrapped) return
        bootstrapped = true
        runCatching { installBootstrap(param.classLoader) }
            .onFailure { log(Log.ERROR, TAG, "install bootstrap failed", it) }
        log(Log.INFO, TAG, "module active in ${param.packageName}")
    }

    private fun installBootstrap(classLoader: ClassLoader) {
        val appClass = Class.forName(APP_CLASS, false, classLoader)
        val attach =
            generateSequence(appClass) { it.superclass }
                .flatMap { it.declaredMethods.asSequence() }
                .firstOrNull { it.name == "attachBaseContext" && it.parameterCount == 1 }
                ?: error("attachBaseContext not found")
        hook(attach).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept { chain ->
            val result = chain.proceed()
            val context = chain.args.firstOrNull() as? Context
            if (context != null) {
                runCatching {
                        AutoStart.requestOnce()
                        startAutomatorFromHost("@gkd/app/xposed/GkdXposedModule")
                    }
                    .onFailure { log(Log.WARN, TAG, "auto start failed", it) }
            }
            result
        }
    }

    private companion object {
        const val TAG = "GKD-Xposed"
        const val META_PACKAGE = "li.songe.gkd"
        const val APP_CLASS = "li.gkd.app.App"
    }
}

package li.gkd.app.platform

import li.gkd.app.DesktopRuntime

actual fun requestAutomatorRestart(): PlatformResult<Unit> {
    DesktopRuntime.requireCurrent().applyAutomatorSettings()
    return PlatformResult.Success(Unit)
}

actual fun requestCompanionServiceUpdate(): PlatformResult<Unit> {
    DesktopRuntime.requireCurrent().updateCompanionService()
    return PlatformResult.Success(Unit)
}

/** Desktop 没有 root 看门狗，开关只影响设置，不模拟系统级行为。 */
actual fun requestRootKeepAliveUpdate() = Unit

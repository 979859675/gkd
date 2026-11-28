package li.gkd.app.priv

import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import li.gkd.app.META
import li.gkd.app.logging.LogUtils

/**
 * 纯 Root 保活：不使用常驻通知，也不使用悬浮窗。
 *
 * 分两层：
 * 1. 一次性加固：把进程的 oom 权重压到 -1000（lmkd 不会回收）、加入 doze 白名单、
 *    放开后台启动与待机分桶限制；
 * 2. 脱离 App 的 root 看门狗 shell：App 进程被杀后由它重新 `am start-service` 拉起
 *    [li.gkd.app.service.RootWakeService]（普通 service，不需要前台通知）。
 *
 * 看门狗由 `setsid` 脱离 App 的会话启动，App 被杀不会带走它；重启设备后由开机广播重新拉起。
 * 脚本优先放在 /data/adb（KernelSU / APatch / Magisk 都持久保留），否则退回 /data/local/tmp。
 */
object RootKeepAlive {
    private const val INTERVAL_SECONDS = 30
    private const val SU_TIMEOUT_SECONDS = 30L
    private const val WAKE_SERVICE_CLASS = "li.gkd.app.service.RootWakeService"

    private const val SCRIPT_NAME = "gkd-watchdog.sh"
    private const val PID_NAME = "gkd-watchdog.pid"
    private const val LOG_NAME = "gkd-watchdog.log"

    /** 两个候选目录，脚本启动后自己判断实际目录。 */
    const val CANDIDATE_DIRS = "/data/adb /data/local/tmp"

    /**
     * 判断 pid 是否真的是看门狗（防止 pid 复用误杀别的进程）。
     * root 身份下可以读取其它进程的 /proc/<pid>/cmdline。
     */
    private const val PID_IS_WATCHDOG =
        "is_wd() { [ -n \"\$1\" ] && kill -0 \"\$1\" 2>/dev/null && " +
            "tr '\\000' ' ' < /proc/\"\$1\"/cmdline 2>/dev/null | grep -q $SCRIPT_NAME; }"

    /**
     * 看门狗脚本本体。用 heredoc 原样写入，`$1` 是启动时传入的工作目录。
     * 注意：这里的 `$` 必须保持字面量，不能被 Kotlin 展开。
     */
    private val LOOP_SCRIPT =
        """
        #!/system/bin/sh
        PKG=__PKG__
        COMPONENT=__COMPONENT__
        DIR=${'$'}1
        PIDF=${'$'}DIR/__PID__
        LOG=${'$'}DIR/__LOG__
        INTERVAL=__INTERVAL__
        [ -z "${'$'}DIR" ] && DIR=/data/local/tmp && PIDF=${'$'}DIR/__PID__ && LOG=${'$'}DIR/__LOG__
        echo ${'$'}${'$'} > ${'$'}PIDF
        log() { echo "${'$'}(date '+%m-%d %H:%M:%S') ${'$'}*" >> ${'$'}LOG; }
        app_pid() {
          P=${'$'}(pidof ${'$'}PKG 2>/dev/null | awk '{print ${'$'}1}')
          if [ -z "${'$'}P" ]; then
            P=${'$'}(ps -A 2>/dev/null | grep -w ${'$'}PKG | grep -v grep | awk 'NR==1{print ${'$'}2}')
          fi
          echo ${'$'}P
        }
        log "watchdog start pid=${'$'}${'$'} dir=${'$'}DIR"
        while :; do
          PID=${'$'}(app_pid)
          if [ -z "${'$'}PID" ]; then
            log "app not running, restart"
            am start-service -n ${'$'}COMPONENT >/dev/null 2>&1 ||
              am startservice -n ${'$'}COMPONENT >/dev/null 2>&1 ||
              cmd activity start-service -n ${'$'}COMPONENT >/dev/null 2>&1
            sleep 5
            PID=${'$'}(app_pid)
            [ -n "${'$'}PID" ] && log "restarted pid=${'$'}PID"
          fi
          [ -n "${'$'}PID" ] && echo -1000 > /proc/${'$'}PID/oom_score_adj 2>/dev/null
          if [ -f ${'$'}LOG ] && [ ${'$'}(wc -c < ${'$'}LOG 2>/dev/null || echo 0) -gt 131072 ]; then
            tail -c 65536 ${'$'}LOG > ${'$'}LOG.tmp 2>/dev/null && mv ${'$'}LOG.tmp ${'$'}LOG
          fi
          sleep ${'$'}INTERVAL
        done
        """.trimIndent()
            .replace("__PKG__", META.appId)
            .replace("__COMPONENT__", "${META.appId}/$WAKE_SERVICE_CLASS")
            .replace("__PID__", PID_NAME)
            .replace("__LOG__", LOG_NAME)
            .replace("__INTERVAL__", INTERVAL_SECONDS.toString())

    /**
     * 一次性加固 + 启动看门狗。整个流程在一次 su 调用里完成，
     * 避免反复弹 root 授权或触发 KernelSU/APatch 的请求限流。
     */
    suspend fun start(): Boolean =
        withContext(Dispatchers.IO) {
            val command =
                buildString {
                    appendLine("PKG=${META.appId}")
                    appendLine("for d in $CANDIDATE_DIRS; do [ -d \"\$d\" ] && [ -w \"\$d\" ] && DIR=\"\$d\" && break; done")
                    appendLine("[ -z \"\$DIR\" ] && DIR=/data/local/tmp")
                    // 1. 进程级：让 lmkd 不回收
                    appendLine("APP_PID=\$(pidof \$PKG 2>/dev/null | awk '{print \$1}')")
                    appendLine("[ -n \"\$APP_PID\" ] && echo -1000 > /proc/\$APP_PID/oom_score_adj 2>/dev/null")
                    // 2. 系统策略：doze 白名单 / 后台启动 / 待机分桶
                    appendLine("cmd appops set \$PKG RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1")
                    appendLine("cmd appops set \$PKG RUN_IN_BACKGROUND allow >/dev/null 2>&1")
                    appendLine("cmd appops set \$PKG WAKE_LOCK allow >/dev/null 2>&1")
                    appendLine("cmd appops set \$PKG START_FOREGROUND allow >/dev/null 2>&1")
                    appendLine("cmd deviceidle whitelist +\$PKG >/dev/null 2>&1")
                    appendLine("dumpsys deviceidle whitelist +\$PKG >/dev/null 2>&1")
                    appendLine("am set-standby-bucket \$PKG active >/dev/null 2>&1")
                    // 3. 停掉旧看门狗，写入新脚本并脱离会话启动
                    appendLine(PID_IS_WATCHDOG)
                    appendLine("OLD=\$(cat \$DIR/$PID_NAME 2>/dev/null)")
                    appendLine("is_wd \"\$OLD\" && kill \$OLD 2>/dev/null")
                    appendLine("rm -f \$DIR/$PID_NAME")
                    appendLine("cat > \$DIR/$SCRIPT_NAME <<'GKD_EOF'")
                    LOOP_SCRIPT.lines().forEach { appendLine(it) }
                    appendLine("GKD_EOF")
                    appendLine("chmod 700 \$DIR/$SCRIPT_NAME")
                    appendLine(
                        "if command -v setsid >/dev/null 2>&1; then " +
                            "setsid sh \$DIR/$SCRIPT_NAME \"\$DIR\" >/dev/null 2>&1 </dev/null & " +
                            "else nohup sh \$DIR/$SCRIPT_NAME \"\$DIR\" >/dev/null 2>&1 </dev/null & fi"
                    )
                    appendLine("sleep 1")
                    appendLine("NEW=\$(cat \$DIR/$PID_NAME 2>/dev/null)")
                    appendLine(
                        "if is_wd \"\$NEW\"; then echo GKD_WATCHDOG=RUNNING:\$DIR; " +
                            "else echo GKD_WATCHDOG=FAILED:\$DIR; fi"
                    )
                }
            val output = su(command) ?: return@withContext false
            val state =
                output.lineSequence().firstOrNull { it.startsWith("GKD_WATCHDOG=") }
                    ?.removePrefix("GKD_WATCHDOG=")?.trim()
            if (state == null || !state.startsWith("RUNNING")) {
                LogUtils.w("RootKeepAlive: 启动看门狗失败 state=$state output=${output.take(500)}")
                return@withContext false
            }
            delay(1.seconds)
            LogUtils.i("RootKeepAlive: 看门狗已启动 $state")
            true
        }

    suspend fun isRunning(): Boolean =
        withContext(Dispatchers.IO) {
            val command =
                "$PID_IS_WATCHDOG; " +
                    "for d in $CANDIDATE_DIRS; do p=\"\$d/$PID_NAME\"; " +
                    "if is_wd \"\$(cat \"\$p\" 2>/dev/null)\"; then echo RUNNING; exit 0; fi; " +
                    "done; echo STOPPED"
            su(command)?.contains("RUNNING") == true
        }

    /** 关闭看门狗。加固项（oom / appops / 白名单）保留，不影响正常使用。 */
    suspend fun stop(): Boolean =
        withContext(Dispatchers.IO) {
            val command =
                "$PID_IS_WATCHDOG; " +
                    "for d in $CANDIDATE_DIRS; do p=\"\$d/$PID_NAME\"; pid=\"\$(cat \"\$p\" 2>/dev/null)\"; " +
                    "is_wd \"\$pid\" && kill \"\$pid\" 2>/dev/null; rm -f \"\$p\"; done; echo STOPPED"
            su(command)?.contains("STOPPED") == true
        }

    /** 读看门狗日志，用于排查为什么没有自动拉起。 */
    suspend fun tailLog(lines: Int = 20): String =
        withContext(Dispatchers.IO) {
            val command =
                "for d in $CANDIDATE_DIRS; do f=\"\$d/$LOG_NAME\"; " +
                    "[ -f \"\$f\" ] && tail -n $lines \"\$f\"; done; true"
            su(command).orEmpty()
        }

    private fun su(command: String): String? =
        try {
            val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(SU_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                LogUtils.w("RootKeepAlive: su 超时")
                return null
            }
            output
        } catch (e: Exception) {
            LogUtils.w("RootKeepAlive: su 执行失败", e)
            null
        }
}

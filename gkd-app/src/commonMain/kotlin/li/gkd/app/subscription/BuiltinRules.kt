package li.gkd.app.subscription

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import li.gkd.app.logging.LogUtils
import li.gkd.app.resources.Res
import li.gkd.app.settings.FileSettingsStorage
import li.gkd.app.storage.appStorage
import li.gkd.db.Db
import li.gkd.db.SubsItem
import li.gkd.db.SubscriptionIds
import li.songe.json5.Json5
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * 仓库内置规则文件夹 `gkd-app/src/commonMain/composeResources/files/builtin_rules/`。
 *
 * 支持两种文件：
 * - 订阅文件（含 `id`/`name`/`apps`）：按自身 id 导入为本地订阅；
 * - 规则库文件（含 `data` 数组，与 https://i.gkd.li/ 导入格式一致）：每个应用生成一条本地订阅。
 *
 * 只导入尚不存在的 id，用户已有的同名订阅不会被覆盖，导入后可在订阅页自由编辑或删除。
 */
object BuiltinRules {
    private val libraryIdBase = 3_0000L

    private val store by lazy { FileSettingsStorage(appStorage().privateStore) }

    /** 已成功导入过的文件名，避免每次启动都重新解析数 MB 的规则。 */
    private fun readImported(): MutableSet<String> =
        store.read(IMPORTED_MARKER).orEmpty().lines().filter { it.isNotBlank() }.toMutableSet()

    private fun writeImported(names: Set<String>) {
        store.writeAtomically(IMPORTED_MARKER, names.sorted().joinToString("\n"))
    }

    @OptIn(ExperimentalResourceApi::class)
    private suspend fun readFile(name: String): List<RawSubscription>? {
        val text =
            try {
                Res.readBytes("files/builtin_rules/$name").decodeToString()
            } catch (e: Exception) {
                LogUtils.w("读取内置规则失败: $name", e)
                return null
            }
        return try {
            parseFile(name, text)
        } catch (e: Exception) {
            LogUtils.w("解析内置规则失败: $name", e)
            null
        }
    }

    private fun parseFile(name: String, text: String): List<RawSubscription> {
        val jsonObject =
            try {
                Json5.parseToJsonElement(text) as? JsonObject
            } catch (_: Exception) {
                null
            }
        val data = jsonObject?.get("data")
        if (data is JsonArray) {
            return parseLibrary(name, data)
        }
        return listOf(RawSubscriptionParser.parse(text, json5 = true))
    }

    private fun parseLibrary(name: String, data: JsonArray): List<RawSubscription> =
        data.withIndex().mapNotNull { (index, item) ->
            val app = item as? JsonObject ?: return@mapNotNull null
            val appId =
                (app["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
            val appName = (app["name"] as? JsonPrimitive)?.content
            val appWithGroups =
                JsonObject(app + ("groups" to (app["groups"] ?: JsonArray(emptyList<JsonElement>()))))
            RawSubscription(
                id = libraryIdBase + index,
                name = "$name · ${appName ?: appId}",
                version = 1,
                author = "GKD 内置规则文件夹",
                apps =
                    listOf(
                        RawSubscriptionParser.parseApp(appWithGroups),
                    ),
            )
        }

    suspend fun importMissing() {
        val imported = readImported()
        val pending = BUILTIN_RULE_FILES.filter { it !in imported }
        if (pending.isEmpty()) return
        val existing = Db.subsItemDao.queryAll().mapTo(mutableSetOf()) { it.id }
        var order = existing.size
        pending.forEach { name ->
            val subscriptions = readFile(name) ?: return@forEach
            subscriptions.forEach { subscription ->
                if (subscription.id in existing || SubscriptionIds.isLocal(subscription.id)) {
                    return@forEach
                }
                existing += subscription.id
                order += 1
                try {
                    SubscriptionRepository.saveWithItem(
                        subscription = subscription,
                        defaultItem = SubsItem(id = subscription.id, order = order, enable = true),
                    )
                } catch (e: Exception) {
                    LogUtils.w("导入内置规则失败: ${subscription.id}", e)
                }
            }
            imported += name
            if (runCatching { writeImported(imported) }.isFailure) {
                LogUtils.w("记录内置规则导入状态失败: $name")
            }
        }
    }
}

/** 记录已导入的文件名，确保内置规则只在首次启动时解析一次。 */
private const val IMPORTED_MARKER = "builtin_rules_imported.txt"

// 资源打包顺序不受文件系统顺序控制，显式列出以免依赖目录遍历。
private val BUILTIN_RULE_FILES =
    listOf(
        "1.json5",
        "2.json5",
        "3-ganlin.json5",
        "4-mengnian.json5",
        "5-volcano.json5",
    )

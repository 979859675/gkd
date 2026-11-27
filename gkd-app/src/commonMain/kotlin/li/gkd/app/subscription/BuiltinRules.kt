package li.gkd.app.subscription

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import li.gkd.app.logging.LogUtils
import li.gkd.app.resources.Res
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

    @OptIn(ExperimentalResourceApi::class)
    private suspend fun readFiles() =
        BUILTIN_RULE_FILES.mapNotNull { name ->
            val text =
                try {
                    Res.readBytes("files/builtin_rules/$name").decodeToString()
                } catch (e: Exception) {
                    LogUtils.w("读取内置规则失败: $name", e)
                    return@mapNotNull null
                }
            try {
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
            RawSubscription(
                id = libraryIdBase + index,
                name = "$name · ${appName ?: appId}",
                version = 1,
                author = "GKD 内置规则文件夹",
                apps =
                    listOf(
                        RawSubscriptionParser.parseApp(
                            JsonObject(app + ("groups" to (app["groups"] ?: JsonArray(emptyList<JsonElement>()))),
                        ),
                    ),
            )
        }

    suspend fun importMissing() {
        val existing = Db.subsItemDao.queryAll().mapTo(mutableSetOf()) { it.id }
        var order = existing.size
        readFiles().flatten().forEach { subscription ->
            if (subscription.id in existing || SubscriptionIds.isLocal(subscription.id)) {
                return@forEach
            }
            order += 1
            try {
                SubscriptionRepository.saveWithItem(
                    subscription = subscription,
                    defaultItem = SubsItem(id = subscription.id, order = order, enable = false),
                )
            } catch (e: Exception) {
                LogUtils.w("导入内置规则失败: ${subscription.id}", e)
            }
        }
    }
}

// 资源打包顺序不受文件系统顺序控制，显式列出以免依赖目录遍历。
private val BUILTIN_RULE_FILES =
    listOf(
        "1-demo-splash.json5",
        "2-demo-confirm.json5",
    )

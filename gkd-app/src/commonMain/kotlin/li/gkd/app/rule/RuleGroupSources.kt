package li.gkd.app.rule

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import li.gkd.app.app.AppInfoRepository
import li.gkd.app.app.applicationScope
import li.gkd.app.app.launcherAppIdFlow
import li.gkd.app.logging.LogUtils
import li.gkd.app.model.AppInfo
import li.gkd.app.state.Loadable
import li.gkd.app.subscription.RawSubscription
import li.gkd.app.subscription.SubscriptionRepository
import li.gkd.app.subscription.SubscriptionSnapshot
import li.gkd.app.subscription.UsedSubsEntry
import li.gkd.db.SubsItem
import li.gkd.db.SubscriptionConfigSnapshot
import li.gkd.db.SubscriptionConfigStore

data class RuleGroupSnapshot(val groups: RuleGroupSummary, val apps: Map<String, AppInfo>)

data class RuleAppEnvironment(val apps: Map<String, AppInfo>, val launcherAppId: String)

object RuleGroupSources {
    fun usedSubscriptions(
        items: List<SubsItem>,
        subscriptions: Map<Long, RawSubscription>,
    ): List<UsedSubsEntry> = items.mapNotNull { item ->
        subscriptions[item.id]
            ?.takeIf { item.enable && it.hasRule }
            ?.let { UsedSubsEntry(item, it) }
    }

    fun observe(
        subscriptions: Flow<Loadable<SubscriptionSnapshot>>,
        environment: Flow<RuleAppEnvironment>,
        configs: Flow<SubscriptionConfigSnapshot>,
    ): Flow<Loadable<RuleGroupSnapshot>> =
        combine(subscriptions, environment, configs) { loaded, input, config ->
                when (loaded) {
                    Loadable.Loading -> Loadable.Loading
                    is Loadable.Failure -> Loadable.Failure(loaded.cause)
                    is Loadable.Ready ->
                        Loadable.Ready(
                            RuleGroupSnapshot(
                                RuleGroupSummaryBuilder.build(
                                    usedSubscriptions(config.subsItems, loaded.value.subscriptions),
                                    input.apps,
                                    config.appConfigs,
                                    config.appGroupConfigs + config.globalGroupConfigs,
                                    config.categoryConfigs,
                                    input.launcherAppId,
                                ),
                                input.apps,
                            ),
                        )
                }
            }
            .catch { emit(Loadable.Failure(it)) }
            .flowOn(Dispatchers.Default)
}

/**
 * 自愈兜底：任一上游在冷启动时始终不发首个值（例如首次应用列表查询被特权服务连接打断），
 * `combine` 就永远不会产出结果，界面和通知会永久停在「配置加载中」，规则也不生效。
 *
 * 超时后主动刷新一次应用列表；仍然拿不到结果就转为 Failure，
 * 让界面显示可重试的失败，而不是无限转圈。
 */
private val selfHealFailure = MutableStateFlow<Throwable?>(null)

private val SELF_HEAL_TIMEOUT = 20.seconds

val ruleGroupState: StateFlow<Loadable<RuleGroupSnapshot>> by lazy {
    val state =
        RuleGroupSources.observe(
                SubscriptionRepository.snapshotFlow,
                combine(AppInfoRepository.appInfoMapFlow, launcherAppIdFlow) { apps, launcher ->
                    RuleAppEnvironment(apps, launcher)
                },
                SubscriptionConfigStore.observe(),
            )
            .combine(selfHealFailure) { loaded, failure ->
                if (loaded is Loadable.Loading && failure != null) Loadable.Failure(failure)
                else loaded
            }
            .stateIn(applicationScope(), SharingStarted.Eagerly, Loadable.Loading)
    applicationScope().launch {
        val ready =
            withTimeoutOrNull(SELF_HEAL_TIMEOUT) {
                state.first { it !is Loadable.Loading }
            }
        if (ready != null) return@launch
        LogUtils.w("规则快照长时间未就绪，主动刷新应用列表自愈")
        runCatching { AppInfoRepository.refresh("@gkd/rule/selfHeal") }
            .onFailure { LogUtils.w("规则快照自愈刷新失败", it) }
        val retried =
            withTimeoutOrNull(SELF_HEAL_TIMEOUT) {
                state.first { it !is Loadable.Loading }
            }
        if (retried == null) {
            selfHealFailure.value = IllegalStateException("规则配置长时间未就绪")
        }
    }
    state
}

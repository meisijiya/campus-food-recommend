package com.meisijiya.campusfood.module.featureflag;

import java.util.Set;

/**
 * Feature Flag 业务接口(F-11 W1)— 业务侧(W3 在 LikeService / RecommendService /
 * MerchantQueryService 加的注解 / 切分支)统一通过本接口查询 flag 状态。
 *
 * <h2>接口契约</h2>
 * <ul>
 *   <li>{@link #isEnabled(String, Long)} — 业务热路径,每次请求可能调;
 *       实现必须用 Caffeine 60s TTL 避免每请求打 Redis。</li>
 *   <li>{@link #getConfig(String)} — admin 端点用,直读缓存;flagKey 不存在时
 *       返回 null(W2 控制器可据此抛 {@code FEATURE_FLAG_NOT_FOUND})。</li>
 *   <li>{@link #setConfig(String, FlagConfig)} — W2 admin POST 接口用;
 *       同步写 Redis hash + 失效 Caffeine。</li>
 *   <li>{@link #listFlags()} — admin 端列表用,返回当前已知 flagKey 集合
 *       (Redis hash 已加载 + 默认 yml 中的 flagKey)。</li>
 * </ul>
 *
 * <h2>线程安全</h2>
 * <p>实现必须线程安全 — 业务侧可能在多线程并发调 {@link #isEnabled}。
 *
 * @author meisijiya
 */
public interface FeatureFlagService {

    /**
     * 判断某 flag 对某 studentId 是否开启。
     *
     * @param flagKey   flag 名(对应 Redis hash field)
     * @param studentId 学生 ID(可为 null,此时 PERCENTAGE / WHITELIST_ONLY 模式
     *                  一律视为 false,ALL_ON 仍 true,ALL_OFF 返回 false)
     * @return 最终决策结果
     */
    boolean isEnabled(String flagKey, Long studentId);

    /**
     * 获取某 flag 当前配置(直接走缓存,不查 Redis)。
     *
     * @param flagKey flag 名
     * @return 配置;flagKey 不存在时返回 null
     */
    FlagConfig getConfig(String flagKey);

    /**
     * 修改某 flag 配置(同步写 Redis hash + 失效 Caffeine)。
     *
     * <p>W2 的 admin POST 接口会调本方法。
     *
     * @param flagKey flag 名
     * @param config  新配置
     */
    void setConfig(String flagKey, FlagConfig config);

    /**
     * 列出当前所有 flagKey(Redis hash 已加载 + 默认 yml 中的 flagKey 合并去重)。
     *
     * @return flagKey 集合;无任何 flag 时返回空集合
     */
    Set<String> listFlags();
}
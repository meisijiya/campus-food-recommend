package com.meisijiya.campusfood.module.featureflag;

/**
 * Feature Flag 灰度模式枚举(F-11 W1)— 定义 4 种策略,业务侧在
 * {@link FeatureFlagService#isEnabled(String, Long)} 调用时按当前
 * {@link FlagConfig#getMode()} 分发决策。
 *
 * <h2>4 种模式语义</h2>
 * <ul>
 *   <li>{@link #ALL_ON} — 永远开启(0% 灰度,默认全量);用于紧急回滚或主路径。</li>
 *   <li>{@link #ALL_OFF} — 永远关闭;用于灰度下线 / 占位 flag。</li>
 *   <li>{@link #WHITELIST_ONLY} — 仅白名单内 {@code studentId} 通过;用于
 *       内部员工 / 种子用户 / 排查 case。</li>
 *   <li>{@link #PERCENTAGE} — 按 {@code flagKey + studentId} 哈希取模 0-100,
 *       严格小于 {@code percentage} 视为开启;同 userId 在同 flag 下结果稳定,
 *       但不同 flag 之间独立分布。</li>
 * </ul>
 *
 * <h2>不变量</h2>
 * <ul>
 *   <li>枚举名 = {@code application.yml} 里的字符串大小写(Jackson {@code @JsonCreator}
 *       会按 {@link #valueOf} 反序列化)。</li>
 *   <li>新增模式必须先开 ADR(§9 不变量),否则不允许在本 enum 加。</li>
 * </ul>
 *
 * @author meisijiya
 */
public enum FlagMode {
    /** 永远开启。 */
    ALL_ON,
    /** 永远关闭。 */
    ALL_OFF,
    /** 仅白名单内 studentId 通过。 */
    WHITELIST_ONLY,
    /** 按 hash 取模百分比灰度。 */
    PERCENTAGE
}
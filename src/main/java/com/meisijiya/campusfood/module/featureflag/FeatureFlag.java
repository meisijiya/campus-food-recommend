package com.meisijiya.campusfood.module.featureflag;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Feature Flag 业务方法注解(F-11 W3)— 把业务方法标记为受某 flag 控制。
 *
 * <p>与 {@link FeatureFlagAspect} 配合使用:Aspect 拦截所有带本注解的方法,
 * 在方法执行前先查 {@link FeatureFlagService#isEnabled(String, Long)},
 * 按决策决定走原方法(true)还是跳过原方法(false,根据返回类型决定 fallback)。
 *
 * <h2>设计取舍</h2>
 * <ul>
 *   <li><b>flagKey 通过 {@link #value()} 指定</b> — 与 Redis hash field 1:1 对应。
 *       业务侧不直接 new Redis key 字符串,统一走 {@code FeatureFlagService} 接口。</li>
 *   <li><b>{@link #defaultOn()} 决定 fallback</b> — flag 不存在 / service 异常 / studentId 为 null
 *       时返回 {@code defaultOn()}。对 {@code boolean} 返回类型,Aspect 用此值避免 NPE。
 *       对其他返回类型,Aspect 直接 {@code return null}(controller 收到 null 后由各业务决定如何处理)。</li>
 *   <li><b>仅 METHOD 级别</b> — 不支持类级 / 参数级;避免与 Spring AOP proxy chain 混乱。</li>
 *   <li><b>RUNTIME 保留</b> — Spring AOP 需要运行时读注解,RetentionPolicy 必须 RUNTIME。</li>
 * </ul>
 *
 * <h2>使用样例</h2>
 * <pre>{@code
 *   @FeatureFlag(value = "like-cache-bypass", defaultOn = false)
 *   public boolean like(String studentId, String merchantId) { ... }
 *
 *   @FeatureFlag(value = "recommend-v2", defaultOn = false)
 *   public RecommendationResult recommend(String sid) { ... }
 *
 *   @FeatureFlag(value = "merchant-detail-new", defaultOn = false)
 *   public Map<String, Object> findDetailById(String merchantId) { ... }
 * }</pre>
 *
 * <h2>AOP 行为契约</h2>
 * <p>见 {@link FeatureFlagAspect}。简述:
 * <ul>
 *   <li>flag 开启 → {@code jp.proceed()}(走原方法)</li>
 *   <li>flag 关闭 + 返回 {@code boolean} → 返 {@link #defaultOn()}</li>
 *   <li>flag 关闭 + 返回 {@code void} → 直接返 null</li>
 *   <li>flag 关闭 + 其他返回类型 → 返 null(控制器需自行兜底)</li>
 * </ul>
 *
 * @author meisijiya
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface FeatureFlag {

    /**
     * flag 名(对应 Redis hash {@code feature_flags} 里的 field 名)。
     *
     * <p>约定:业务侧应使用 Redis hash 已注册或 yml {@code feature-flag.default-flags} 已配置的 key,
     * 未知 key 的 {@code isEnabled} 安全默认返回 {@code false}。
     */
    String value();

    /**
     * flag 不存在 / service 异常时的默认决策。
     *
     * <p>对 {@code boolean} 返回方法直接用此值;其他返回类型被 Aspect 跳过时返 null,
     * 此时 {@code defaultOn()} 仅作为 Aspect 日志记录(不会真正用上)。
     */
    boolean defaultOn() default false;
}
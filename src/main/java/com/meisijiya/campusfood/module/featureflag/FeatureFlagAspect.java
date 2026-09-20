package com.meisijiya.campusfood.module.featureflag;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Map;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestParam;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Feature Flag 切面(F-11 W3)— 拦截所有带 {@link FeatureFlag} 注解的方法,
 * 按 {@link FeatureFlagService#isEnabled(String, Long)} 决策走原方法或跳过。
 *
 * <h2>工作流</h2>
 * <pre>
 *   业务方法调用(like / recommend / findDetailById)
 *      │
 *      ▼
 *   Aspect.@Around 拦截
 *      │
 *      ├── studentId 解析(优先级:Long → @RequestParam studentId → "sid" 类参数名 → null)
 *      │
 *      ▼
 *   featureFlagService.isEnabled(flagKey, studentId)
 *      │
 *      ├── true  → jp.proceed()(走原方法,记录 metric / log)
 *      │
 *      └── false → 根据方法返回类型返回 fallback(boolean → defaultOn / void → null / 其他 → null)
 * </pre>
 *
 * <h2>studentId 解析策略</h2>
 * <p>业务方法参数形如 {@code like(String studentId, String merchantId)} 或
 * {@code recommend(String sid)} — Aspect 按以下优先级解析:
 * <ol>
 *   <li>第一个 {@link Long} / {@code long} 类型参数(覆盖 {@code like(Long userId, Long merchantId)});</li>
 *   <li>带 {@link RequestParam @RequestParam("studentId")} 注解的参数(controller 透传场景);</li>
 *   <li>参数名是 {@code studentId} / {@code sid} / {@code userId} 的任意类型参数(Long 直接用 / String 尝试
 *       {@link Long#parseLong(String)} / 其他类型返 null);</li>
 *   <li>都找不到 → {@code null},由 {@link FeatureFlagService} 安全默认决定(ALL_ON 仍 true,其余 false)。</li>
 * </ol>
 *
 * <h2>fallback 策略</h2>
 * <ul>
 *   <li><b>boolean 返回类型</b>:直接返 {@link FeatureFlag#defaultOn()}。这是 LikeService.like() 的硬要求
 *       —— boolean 不能 unbox null,否则 NPE。</li>
 *   <li><b>void 返回类型</b>:不调 proceed,直接返 null(Spring AOP 对 void 返 null 也允许)。</li>
 *   <li><b>其他引用类型</b>:返 null,controller 自行兜底。
 *       如需返回更具体的 "skip 语义响应",业务方法可用 DTO 包装。</li>
 * </ul>
 *
 * <h2>指标</h2>
 * <p>每次 {@code jp.proceed()} 成功执行,自增 {@code flag_hit_timer_seconds{flag}} Timer —
 * 验证 flag 开启时业务方法的实际耗时,与 {@code feature_flag_check_total{decision=false}} 反向对齐
 * (P99 差异 / QPS 占比)。{@link FeatureFlagService} 内部已经打了
 * {@code feature_flag_check_total{flag, decision}},本 Aspect 不重复打 Counter。
 *
 * <h2>不破坏现有调用栈</h2>
 * <ul>
 *   <li>Spring AOP 对 {@code @Around} 抛异常时,会传播给调用方 — Aspect 在 proceed() 处加 try/catch
 *       仅捕获业务异常包装成本 Aspect 自身异常,不吞原异常(保留业务堆栈)。</li>
 *   <li>不支持类级注解(只切方法),与 {@code @Transactional} 等其他 AOP advice 共存时
 *       可能产生 ordering 问题 — 用 {@link Order} 锚定 aspect 在最外层。</li>
 * </ul>
 *
 * @author meisijiya
 */
@Aspect
@Component
@Order(0)  // 最外层 Aspect(数字越小优先级越高);业务方法级 @Transactional 之类 advice 在内层
public class FeatureFlagAspect {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlagAspect.class);

    private final FeatureFlagService featureFlagService;
    private final MeterRegistry meterRegistry;

    public FeatureFlagAspect(FeatureFlagService featureFlagService,
                             MeterRegistry meterRegistry) {
        if (featureFlagService == null) {
            throw new IllegalArgumentException("featureFlagService must be non-null");
        }
        this.featureFlagService = featureFlagService;
        // MeterRegistry 可为 null(测试场景)— 缺 metric 时仅记录 log,不阻断业务
        this.meterRegistry = meterRegistry;
    }

    /**
     * 切入点:所有带 {@link FeatureFlag} 注解的方法。
     *
     * <p>注意 {@code @annotation(featureFlag)} 的参数名 {@code featureFlag} 必须与
     * 方法签名 {@code FeatureFlag featureFlag} 一致 — Spring AOP 通过这个名字把
     * 注解实例反射注入到 advice 方法。
     */
    @Around("@annotation(featureFlag)")
    public Object around(ProceedingJoinPoint jp, FeatureFlag featureFlag) throws Throwable {
        String flagKey = featureFlag.value();
        boolean defaultOn = featureFlag.defaultOn();
        Long studentId = resolveStudentId(jp);

        boolean enabled;
        try {
            enabled = featureFlagService.isEnabled(flagKey, studentId);
        } catch (RuntimeException e) {
            // service 异常不能阻塞业务 — 安全默认 = defaultOn
            log.warn("FeatureFlagAspect.isEnabled call failed, falling back to defaultOn={}, flag={} err={}",
                    defaultOn, flagKey, e.toString());
            enabled = defaultOn;
        }

        if (!enabled) {
            log.debug("FeatureFlagAspect: flag={} disabled (studentId={}), skipping method",
                    flagKey, studentId);
            return resolveSkipReturn(jp, defaultOn);
        }

        // flag 开启 — 走原方法,并记录 timer metric(若 MeterRegistry 可用)
        // flag_hit_timer_seconds{flag} 验证 flag 开启时业务方法的实际耗时,
        // 与 feature_flag_check_total{decision=false} 反向对齐(P99 差异 / QPS 占比)
        Timer.Sample sample = meterRegistry == null
                ? null
                : Timer.start(meterRegistry);
        try {
            Object result = jp.proceed();
            // F-11 W3 demo:merchant-detail-new flag 开启命中分支往 Map 加 openHours 字段
            // (避免改 Merchant.java 加字段,符合 §18.4 文件所有权表只读约束)
            if ("merchant-detail-new".equals(flagKey) && result instanceof Map<?, ?> resultMap) {
                injectMerchantDetailFlag(resultMap);
            }
            if (sample != null) {
                sample.stop(Timer.builder("flag_hit_timer_seconds")
                        .description("F-11 W3:Feature flag enabled-path method latency")
                        .tag("flag", flagKey)
                        .register(meterRegistry));
            }
            return result;
        } catch (Throwable bizErr) {
            // 业务异常透传(不包装),但先 stop timer,保证 metric 完整
            if (sample != null) {
                sample.stop(Timer.builder("flag_hit_timer_seconds")
                        .description("F-11 W3:Feature flag enabled-path method latency")
                        .tag("flag", flagKey)
                        .register(meterRegistry));
            }
            throw bizErr;
        }
    }

    /**
     * 给 {@code merchant-detail-new} flag 开启分支的 Map 返回值注入增量字段:
     * {@code openHours="09:00-22:00"} + {@code featureFlag="merchant-detail-new:ON"}。
     *
     * <p>之所以在 Aspect 而不是 Service 层做 —
     * Service 层 {@link com.meisijiya.campusfood.module.catalog.MerchantQueryService#findDetailById(String)}
     * 的方法体只构造基本字段,flag 增量字段由 Aspect 注入,体现"功能开关由 AOP 控制"的可观察边界。
     */
    private static void injectMerchantDetailFlag(Map<?, ?> resultMap) {
        if (resultMap == null) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) resultMap;
        m.putIfAbsent("openHours", "09:00-22:00");
        m.putIfAbsent("featureFlag", "merchant-detail-new:ON");
    }

    /**
     * 解析 studentId — 见类注释的优先级。
     *
     * @return studentId;找不到返 null
     */
    private static Long resolveStudentId(ProceedingJoinPoint jp) {
        MethodSignature sig = (MethodSignature) jp.getSignature();
        Method method = sig.getMethod();
        Class<?>[] paramTypes = method.getParameterTypes();
        Annotation[][] paramAnnotations = method.getParameterAnnotations();
        Object[] args = jp.getArgs();

        // 优先级 1:第一个 Long / long 类型参数(覆盖 like(Long, Long) / recommend(Long))
        for (int i = 0; i < paramTypes.length; i++) {
            if (paramTypes[i] == Long.class || paramTypes[i] == long.class) {
                if (i < args.length && args[i] instanceof Long sid) {
                    return sid;
                }
            }
        }

        // 优先级 2:带 @RequestParam("studentId") 注解的参数(controller 透传场景)
        for (int i = 0; i < paramAnnotations.length; i++) {
            for (Annotation a : paramAnnotations[i]) {
                if (a instanceof RequestParam rp && "studentId".equals(rp.name())) {
                    Long resolved = tryParseLong(args, i);
                    if (resolved != null) {
                        return resolved;
                    }
                }
            }
        }

        // 优先级 3:参数名是 studentId / sid / userId 的任意类型参数
        //         (覆盖 LikeService.like(String studentId, ...) / RecommendService.recommend(String sid))
        //         String → Long.parseLong,Long → 直接,其他类型 → null
        java.lang.reflect.Parameter[] params = method.getParameters();
        for (int i = 0; i < params.length; i++) {
            String name = params[i].getName();
            if ("studentId".equals(name) || "sid".equals(name) || "userId".equals(name)) {
                Long resolved = tryParseLong(args, i);
                if (resolved != null) {
                    return resolved;
                }
            }
        }

        return null;
    }

    /**
     * 尝试从 args[i] 解析 Long:Long 直接用,Long 包装类直接用,String 尝试 parseLong,
     * 其他类型返 null。
     */
    private static Long tryParseLong(Object[] args, int index) {
        if (index >= args.length || args[index] == null) {
            return null;
        }
        Object v = args[index];
        if (v instanceof Long l) {
            return l;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 跳过原方法时,根据方法返回类型构造 fallback 值。
     *
     * @param jp         切入点
     * @param defaultOn  {@link FeatureFlag#defaultOn()} — 用于 boolean 返回类型
     * @return boolean 返回 → defaultOn;void 返回 → null;其他 → null
     */
    private static Object resolveSkipReturn(ProceedingJoinPoint jp, boolean defaultOn) {
        MethodSignature sig = (MethodSignature) jp.getSignature();
        Class<?> returnType = sig.getReturnType();
        if (returnType == boolean.class) {
            return defaultOn;
        }
        if (returnType == void.class) {
            return null;
        }
        // 其他引用类型(包括 Boolean 包装类型 / Object / Map / record)— 一律返 null
        return null;
    }
}
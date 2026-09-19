package com.meisijiya.campusfood.module.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meisijiya.campusfood.common.ApiResponse;
import com.meisijiya.campusfood.common.exception.ErrorCode;
import com.meisijiya.campusfood.module.auth.JwtService;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * F-7 W2:HTTP 限流过滤器 — 双层令牌桶(API 全局 + 用户级),降级到 Caffeine 本地桶。
 *
 * <h2>过滤器链位置</h2>
 * <p>通过 {@code SecurityConfig.addFilterBefore(rateLimitFilter, JwtAuthenticationFilter.class)}
 * 注册到 Spring Security chain:<strong>在 JwtAuthenticationFilter 之前执行</strong>。
 * 因此本过滤器<strong>不能依赖</strong> {@code SecurityContextHolder} 拿用户身份 —
 * 那一阶段 JwtFilter 还没设 context。本过滤器自己解析 Bearer token 抽 sid(走
 * {@link JwtService#extractUsername(String)},不验签 — 验签是 JwtFilter 的活,
 * 本过滤器只拿 sid 当桶 key,sid 不存在时 user 桶 key 用 {@code user:anonymous} 兜底)。
 *
 * <h2>双层校验顺序</h2>
 * <ol>
 *   <li>API 全局桶 {@code api:{METHOD}:{URI}} — 先校验,优先保护系统总流量</li>
 *   <li>用户级桶 {@code user:{sid}} — 后校验,保护单用户滥用</li>
 *   <li>任一层 tryAcquire 返回 false → 立即 429(短路,不查第二层避免 Redis 浪费)</li>
 * </ol>
 *
 * <h2>降级路径</h2>
 * <p>Redis 主桶抛 {@link RateLimiterBackendException}(连接失败 / Lua 异常):
 * <ol>
 *   <li>{@link RateLimitDegradationMonitor#recordDegradation(String)} 自增</li>
 *   <li>日志 WARN — 含桶 key + 异常摘要</li>
 *   <li>切到 {@code caffeineLimiter} 重试 — caffeine 永可达,只可能"业务拒绝"</li>
 *   <li>caffeine 也拒绝 → 429 + Retry-After=1s + JSON 错误体</li>
 * </ol>
 *
 * <h2>Bean 注入契约(W1 必须遵守)</h2>
 * <ul>
 *   <li>{@code redisUserRateLimiter} — Redis TokenBucket,capacity=100,refill=10/s(W1 创建)</li>
 *   <li>{@code redisApiRateLimiter} — Redis TokenBucket,capacity=1000,refill=500/s(W1 创建)</li>
 *   <li>{@code caffeineUserRateLimiter} — CaffeineLocalBucket,同 capacity / refill(本类创建)</li>
 *   <li>{@code caffeineApiRateLimiter} — CaffeineLocalBucket,同 capacity / refill(本类创建)</li>
 * </ul>
 *
 * <h2>放行路径</h2>
 * <p>{@link #shouldNotFilter} 显式跳过 {@code /api/auth/**}(登录/刷新不应被自己限流)
 * 和 {@code /actuator/**}(健康检查走 k8s 探针,被限流会触发 pod 误杀)。其他路径
 * 都过双层,即使后续 Spring Security 走 {@code permitAll} 也会跑本过滤器。
 *
 * @author meisijiya
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    /** 用户身份拿不到时的兜底桶 key — 单 IP 共享一个桶,比单用户略宽松但仍是限流。 */
    private static final String ANONYMOUS_USER_KEY = "user:anonymous";
    /** 429 响应 {@code Retry-After} 默认值(秒)— token bucket 下一秒就会补充。 */
    private static final int DEFAULT_RETRY_AFTER_SECONDS = 1;

    private final RateLimiter redisUserLimiter;
    private final RateLimiter redisApiLimiter;
    private final RateLimiter caffeineUserLimiter;
    private final RateLimiter caffeineApiLimiter;
    private final RateLimitDegradationMonitor degradationMonitor;
    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(
            @Qualifier("redisUserRateLimiter") RateLimiter redisUserLimiter,
            @Qualifier("redisApiRateLimiter") RateLimiter redisApiLimiter,
            @Qualifier("caffeineUserRateLimiter") RateLimiter caffeineUserLimiter,
            @Qualifier("caffeineApiRateLimiter") RateLimiter caffeineApiLimiter,
            RateLimitDegradationMonitor degradationMonitor,
            JwtService jwtService,
            ObjectMapper objectMapper) {
        this.redisUserLimiter = redisUserLimiter;
        this.redisApiLimiter = redisApiLimiter;
        this.caffeineUserLimiter = caffeineUserLimiter;
        this.caffeineApiLimiter = caffeineApiLimiter;
        this.degradationMonitor = degradationMonitor;
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String uri = request.getRequestURI();
        // /api/auth/** — 登录 / refresh 不该被自己限流(否则新用户登录会被拒)
        // /actuator/** — k8s 探针,被限流会触发 pod 误杀
        return uri.startsWith("/api/auth/")
                || uri.startsWith("/actuator/")
                // /error — Spring MVC 内置错误页转发,不应触发限流
                || uri.equals("/error");
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String apiBucketKey = buildApiBucketKey(request);
        String userBucketKey = buildUserBucketKey(request);

        // 第一层:API 全局桶 — 优先保护系统总流量
        if (!acquireWithFallback(apiBucketKey, redisApiLimiter, caffeineApiLimiter, "api")) {
            writeRateLimited(response, apiBucketKey);
            return;
        }

        // 第二层:用户级桶 — 保护单用户滥用
        if (!acquireWithFallback(userBucketKey, redisUserLimiter, caffeineUserLimiter, "user")) {
            writeRateLimited(response, userBucketKey);
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * 先走 Redis 主路径,异常时切 Caffeine。返回 true = 放行,false = 业务拒绝(两层均拒)。
     */
    private boolean acquireWithFallback(String bucketKey,
                                        RateLimiter redisLimiter,
                                        RateLimiter caffLimiter,
                                        String layer) {
        try {
            return redisLimiter.tryAcquire(bucketKey);
        } catch (RateLimiterBackendException ex) {
            // 降级:Redis 不可达 → 切 Caffeine 本地桶
            degradationMonitor.recordDegradation(RateLimitDegradationMonitor.SOURCE_REDIS_TO_CAFFEINE);
            log.warn("F-7 rate limiter degraded: redis unreachable on {} layer (key={}), falling back to Caffeine. cause={}",
                    layer, bucketKey, ex.getMessage());
            try {
                return caffLimiter.tryAcquire(bucketKey);
            } catch (RuntimeException caffeineEx) {
                log.error("F-7 rate limiter Caffeine fallback also failed on {} layer (key={}); rejecting request",
                        layer, bucketKey, caffeineEx);
                return false;
            }
        }
    }

    private static String buildApiBucketKey(HttpServletRequest request) {
        // api:{METHOD}:{URI} — method+path 一起做 key,避免 GET /a 和 POST /a 共享桶
        return "api:" + request.getMethod() + ":" + request.getRequestURI();
    }

    private String buildUserBucketKey(HttpServletRequest request) {
        String header = request.getHeader(AUTH_HEADER);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return ANONYMOUS_USER_KEY;
        }
        String token = header.substring(BEARER_PREFIX.length());
        try {
            String username = jwtService.extractUsername(token);
            if (username == null || username.isBlank()) {
                return ANONYMOUS_USER_KEY;
            }
            return "user:" + username;
        } catch (JwtException | IllegalArgumentException e) {
            return ANONYMOUS_USER_KEY;
        }
    }

    private void writeRateLimited(HttpServletResponse response, String bucketKey) throws IOException {
        // HttpStatus 429 — 优先用 SC 常量,避免引入 Spring 枚举在 servlet 上下文里
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(DEFAULT_RETRY_AFTER_SECONDS));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        ApiResponse<Void> body = ApiResponse.fail(ErrorCode.RATE_LIMITED, "rate limited");
        objectMapper.writeValue(response.getWriter(), body);
        log.debug("F-7 rate limited: bucket={} status=429 retry-after={}s", bucketKey, DEFAULT_RETRY_AFTER_SECONDS);
    }
}

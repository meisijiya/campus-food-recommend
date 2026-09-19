package com.meisijiya.campusfood.module.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 限流配置属性(F-7 W1)— {@code rate-limit.*} 前缀,被
 * {@code @ConfigurationPropertiesScan} 在主类自动装配。
 *
 * <h2>字段语义与 {@code application.yml} 对齐</h2>
 * <pre>
 *   rate-limit:
 *     user:
 *       burst: 100      # 用户级桶容量
 *       rate:  10.0     # 用户级桶每秒补充令牌数
 *     api:
 *       burst: 1000     # API 全局桶容量
 *       rate:  500.0    # API 全局桶每秒补充令牌数
 *     script-location: scripts/   # Lua 脚本 classpath 目录
 * </pre>
 *
 * <h2>与 W2 对齐</h2>
 * <p>key 名(扁平 {@code rate-limit.user.burst})与 W2 的 {@code RateLimitFallbackConfig}
 * 兼容;Caffeine 降级桶和 Redis 主桶读同一组配置,降级后行为不漂移。
 *
 * @author meisijiya
 */
@ConfigurationProperties(prefix = "rate-limit")
public class RateLimitProperties {

    public static final int DEFAULT_USER_BURST = 100;
    public static final double DEFAULT_USER_RATE = 10.0;
    public static final int DEFAULT_API_BURST = 1000;
    public static final double DEFAULT_API_RATE = 500.0;
    public static final String DEFAULT_SCRIPT_LOCATION = "scripts/";

    private User user = new User();
    private Api api = new Api();
    private String scriptLocation = DEFAULT_SCRIPT_LOCATION;

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Api getApi() {
        return api;
    }

    public void setApi(Api api) {
        this.api = api;
    }

    public String getScriptLocation() {
        return scriptLocation;
    }

    public void setScriptLocation(String scriptLocation) {
        this.scriptLocation = scriptLocation;
    }

    /** 用户级桶参数子段,在 {@code application.yml} 用 {@code rate-limit.user.*} 绑定。 */
    public static class User {
        private int burst = DEFAULT_USER_BURST;
        private double rate = DEFAULT_USER_RATE;

        public int getBurst() {
            return burst;
        }

        public void setBurst(int burst) {
            this.burst = burst;
        }

        public double getRate() {
            return rate;
        }

        public void setRate(double rate) {
            this.rate = rate;
        }
    }

    /** API 全局桶参数子段,在 {@code application.yml} 用 {@code rate-limit.api.*} 绑定。 */
    public static class Api {
        private int burst = DEFAULT_API_BURST;
        private double rate = DEFAULT_API_RATE;

        public int getBurst() {
            return burst;
        }

        public void setBurst(int burst) {
            this.burst = burst;
        }

        public double getRate() {
            return rate;
        }

        public void setRate(double rate) {
            this.rate = rate;
        }
    }
}

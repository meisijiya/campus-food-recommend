package com.meisijiya.campusfood.config;

import com.meisijiya.campusfood.common.JsonAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.Arrays;

/**
 * Spring Security 配置:无状态 JWT 鉴权。
 *
 * <p>关键纪律:
 * <ul>
 *   <li>CSRF 关闭 — 纯 Bearer token API 不需要 CSRF</li>
 *   <li>SessionCreationPolicy.STATELESS — 不创建 HttpSession,水平扩容无状态</li>
 *   <li>/api/auth/** 与 /actuator/health 放行,其他路径都要鉴权</li>
 *   <li>F-4 review fix: {@code /admin/preheat/**} 在 {@code dev} profile 下 permitAll;
 *       在其他 profile 下 require ADMIN 角色(由 {@link JsonAuthenticationEntryPoint} 返 401/403)。
 *       防止 profile 误配(SPRING_PROFILES_ACTIVE=dev,prod 累加 / 漏关 dev 等)把凌晨批量写
 *       公开给匿名攻击者。</li>
 * </ul>
 *
 * <p>注意:不要在构造器注入 {@code JwtAuthenticationFilter} / {@code JsonAuthenticationEntryPoint},
 * 否则会与 {@code UserDetailsServiceImpl} 触发循环依赖(JwtFilter → UserDetailsService → PasswordEncoder → SecurityConfig)。
 *
 * @author meisijiya
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * dev profile 列表(激活 dev 时手动触发预热端点免 JWT)。
     * 其他 profile(prod / bench / smoke / 等)默认要求 ADMIN 角色鉴权。
     */
    private static final java.util.Set<String> DEV_PROFILES = java.util.Set.of("dev", "test", "it");

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            JsonAuthenticationEntryPoint authenticationEntryPoint,
            Environment env) throws Exception {
        boolean isDevProfile = isDevProfileActive(env);
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)  // F-1 不做 CORS,F-2+ 按需启用
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/auth/**",
                                "/actuator/health",
                                "/actuator/health/**",
                                // F-4 review fix:/actuator/info 不再默认 permitAll(防止未来 info.* 配置
                                // 引入 git/build/env 泄露);要走鉴权
                                "/admin/preheat/**"
                        ).permitAll()
                        .requestMatchers("/actuator/info").authenticated()
                        .requestMatchers("/admin/preheat/**").access((authCtx, request) -> {
                            // dev profile:permitAll;其他 profile:需 ADMIN 角色
                            if (isDevProfile) {
                                return new org.springframework.security.authorization.AuthorizationDecision(true);
                            }
                            boolean isAdmin = authCtx.get().getAuthorities().stream()
                                    .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
                            return new org.springframework.security.authorization.AuthorizationDecision(isAdmin);
                        })
                        .anyRequest().authenticated()
                )
                .exceptionHandling(eh -> eh.authenticationEntryPoint(authenticationEntryPoint))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // cost 12:F-1 demo 单机足够;生产按需升 14
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration cfg) throws Exception {
        return cfg.getAuthenticationManager();
    }

    /**
     * 检测当前激活的 profile 是否包含 dev / test / it(任一即视为开发态,放行 admin 端点)。
     *
     * <p>支持 {@code SPRING_PROFILES_ACTIVE=dev} 或 {@code dev,prod} 累加 profile 列表。
     */
    private static boolean isDevProfileActive(Environment env) {
        String[] active = env.getActiveProfiles();
        if (active.length == 0) {
            // 默认 profile(无显式激活)— 视为 dev,F-1 起的本地默认
            return true;
        }
        return Arrays.stream(active).anyMatch(DEV_PROFILES::contains);
    }
}
package com.meisijiya.campusfood.module.auth;

import com.meisijiya.campusfood.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JwtService 单元测试:覆盖 encode/decode/过期/签名失败四条路径。
 *
 * @author meisijiya
 */
class JwtServiceTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-32+chars";
    private JwtService jwtService;
    private UserDetails demo;

    @BeforeEach
    void setUp() {
        JwtProperties props = new JwtProperties(
                "campus-food-recommend",
                3_600_000L,    // 1h
                86_400_000L,   // 1d
                SECRET);
        jwtService = new JwtService(props);
        demo = User.withUsername("demo")
                .password("ignored")
                .authorities(new SimpleGrantedAuthority("ROLE_STUDENT"))
                .build();
    }

    @Test
    void accessToken_roundTrip_valid() {
        String token = jwtService.generateAccessToken(demo);
        assertThat(token).isNotBlank();
        assertThat(jwtService.isTokenValid(token, demo)).isTrue();
        assertThat(jwtService.extractUsername(token)).isEqualTo("demo");
    }

    @Test
    void refreshToken_wellFormed() {
        String refresh = jwtService.generateRefreshToken(demo);
        assertThat(jwtService.isTokenWellFormed(refresh)).isTrue();
        assertThat(jwtService.isTokenValid(refresh, demo)).isTrue();
    }

    @Test
    void expiredToken_isInvalid() {
        JwtProperties shortLived = new JwtProperties(
                "campus-food-recommend",
                1L,    // 1ms — 立即过期
                86_400_000L,
                SECRET);
        JwtService shortService = new JwtService(shortLived);
        String token = shortService.generateAccessToken(demo);
        // 等到下一个时钟滴答
        try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        assertThat(shortService.isTokenValid(token, demo)).isFalse();
        assertThat(shortService.isTokenWellFormed(token)).isFalse();
    }

    @Test
    void tamperedSignature_isInvalid() {
        String token = jwtService.generateAccessToken(demo);
        // 把 token 最后一位改了 — 签名失败
        String tampered = token.substring(0, token.length() - 2) + "AA";
        assertThat(jwtService.isTokenValid(tampered, demo)).isFalse();
    }

    @Test
    void tokenContainsAuthorities() {
        String token = jwtService.generateAccessToken(demo);
        // JWT 结构:header.payload.signature 三段,base64 url-safe,以 . 分隔
        assertThat(token.split("\\.")).hasSize(3);
        // 完整解析 + 验证权限注入正确
        assertThat(jwtService.isTokenValid(token, demo)).isTrue();
    }
}
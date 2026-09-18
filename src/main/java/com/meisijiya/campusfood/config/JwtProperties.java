package com.meisijiya.campusfood.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * JWT 配置(从 application.yml 的 jwt.* 绑定)。
 *
 * @param issuer  JWT issuer(每次校验)
 * @param accessTokenExpiration  access token 有效期(毫秒,F-1 默认 2h)
 * @param refreshTokenExpiration refresh token 有效期(毫秒,F-1 默认 14d)
 * @param secret   HMAC-SHA256 签名密钥(至少 256 bit,从环境变量读)
 */
@ConfigurationProperties(prefix = "jwt")
@Validated
public record JwtProperties(
        @NotBlank String issuer,
        @Positive long accessTokenExpiration,
        @Positive long refreshTokenExpiration,
        @NotBlank String secret
) {
}
package com.meisijiya.campusfood.module.auth;

import com.meisijiya.campusfood.common.ApiResponse;
import com.meisijiya.campusfood.common.exception.ApiException;
import com.meisijiya.campusfood.config.JwtProperties;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Service;

/**
 * 鉴权业务:登录、刷新。
 *
 * @author meisijiya
 */
@Service
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final UserDetailsService userDetailsService;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;

    public AuthService(AuthenticationManager authenticationManager,
                       UserDetailsService userDetailsService,
                       JwtService jwtService,
                       JwtProperties jwtProperties) {
        this.authenticationManager = authenticationManager;
        this.userDetailsService = userDetailsService;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
    }

    public AuthResponse login(LoginRequest request) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        } catch (BadCredentialsException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        } catch (AuthenticationException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "鉴权失败");
        }
        UserDetails userDetails = userDetailsService.loadUserByUsername(request.username());
        String access = jwtService.generateAccessToken(userDetails);
        String refresh = jwtService.generateRefreshToken(userDetails);
        return new AuthResponse(access, refresh, jwtProperties.accessTokenExpiration());
    }

    public AuthResponse refresh(RefreshRequest request) {
        String token = request.refreshToken();
        // 简单校验:签名 + 过期 + 类型
        if (!jwtService.isTokenWellFormed(token)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "refresh token 无效");
        }
        String username;
        try {
            username = jwtService.extractUsername(token);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "refresh token 解析失败");
        }
        UserDetails userDetails = userDetailsService.loadUserByUsername(username);
        if (!jwtService.isTokenValid(token, userDetails)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "refresh token 与用户不匹配");
        }
        String newAccess = jwtService.generateAccessToken(userDetails);
        String newRefresh = jwtService.generateRefreshToken(userDetails);
        return new AuthResponse(newAccess, newRefresh, jwtProperties.accessTokenExpiration());
    }

    /** 登录响应(access + refresh + ttl)。 */
    public record AuthResponse(String accessToken, String refreshToken, long expiresIn) {}

    /** 登录请求 DTO。 */
    public record LoginRequest(String username, String password) {}

    /** refresh 请求 DTO。 */
    public record RefreshRequest(String refreshToken) {}

    /**
     * 框架预留类型 — 当前未使用,占位避免 ApiResponse 未被引用。
     */
    @SuppressWarnings("unused")
    private ApiResponse<Void> unused() {
        return ApiResponse.ok();
    }
}
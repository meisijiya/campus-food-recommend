package com.meisijiya.campusfood.module.auth;

import com.meisijiya.campusfood.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 鉴权端点:F-1 只暴露 /login 与 /refresh。
 *
 * @author meisijiya
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<AuthService.AuthResponse> login(@Valid @RequestBody LoginRequestDto body) {
        return ApiResponse.ok(authService.login(
                new AuthService.LoginRequest(body.username(), body.password())));
    }

    @PostMapping("/refresh")
    public ApiResponse<AuthService.AuthResponse> refresh(@Valid @RequestBody RefreshRequestDto body) {
        return ApiResponse.ok(authService.refresh(
                new AuthService.RefreshRequest(body.refreshToken())));
    }

    public record LoginRequestDto(@NotBlank String username, @NotBlank String password) {}

    public record RefreshRequestDto(@NotBlank String refreshToken) {}
}
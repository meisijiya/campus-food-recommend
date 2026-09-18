package com.meisijiya.campusfood.module.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/**
 * 内存用户(F-1 demo 用,无 MySQL 用户表)。
 *
 * <p>预置账号:
 * <ul>
 *   <li>demo / demo — 学生角色,日常推荐路径</li>
 *   <li>admin / admin — 管理员,后台预热端点</li>
 * </ul>
 *
 * <p>F-2+ 可替换为 JPA + MySQL {@code Student} 表。
 *
 * @author meisijiya
 */
@Configuration
public class UserDetailsServiceImpl {

    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder passwordEncoder) {
        UserDetails demo = User.builder()
                .username("demo")
                .password(passwordEncoder.encode("demo"))
                .roles("STUDENT")
                .build();

        UserDetails admin = User.builder()
                .username("admin")
                .password(passwordEncoder.encode("admin"))
                .roles("STUDENT", "ADMIN")
                .build();

        return new InMemoryUserDetailsManager(demo, admin);
    }
}
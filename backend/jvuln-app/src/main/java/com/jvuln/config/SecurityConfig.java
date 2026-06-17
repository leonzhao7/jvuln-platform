package com.jvuln.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security 配置
 *
 * 提供基本的认证和授权机制，保护 API 端点
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * 配置 HTTP 安全规则
     *
     * 注意：当前为开发环境配置，生产环境需要：
     * 1. 启用 CSRF 保护
     * 2. 配置 HTTPS
     * 3. 使用数据库存储用户信息
     * 4. 实现基于角色的访问控制
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // 授权规则（Spring Boot 2.7.x API）
            .authorizeRequests()
                // 健康检查端点允许匿名访问
                .antMatchers("/actuator/health").permitAll()
                // 分析相关 API 需要 USER 角色
                .antMatchers("/api/analysis/**").hasRole("USER")
                // 配置相关 API 需要 ADMIN 角色
                .antMatchers("/api/config/**").hasRole("ADMIN")
                // 其他请求需要认证
                .anyRequest().authenticated()
            .and()
            // 使用 HTTP Basic 认证（简单但不推荐用于生产环境）
            .httpBasic()
            .and()
            // 暂时禁用 CSRF（开发环境）
            // 生产环境应启用 CSRF 保护
            .csrf().disable();

        return http.build();
    }

    /**
     * 密码编码器
     * 使用 BCrypt 算法
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}

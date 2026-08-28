package com.app.docreview.reviewcore.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Demo-mode security. Architecture §33.
 *
 * <p><strong>Authorization is fully enforced here</strong> — §33 requires it. Only the
 * authentication MECHANISM is simplified: seeded users with encoded passwords instead of an IdP. A
 * demo mode that also disabled authorization would give false confidence, because the role checks
 * would never be exercised until they were needed in production.
 *
 * <p>Guarded by {@code docreview.security.mode=demo} and NOT the default, so a deployment without
 * that property set gets no security chain rather than an accidentally open one.
 */
@Configuration
@EnableMethodSecurity
@ConditionalOnProperty(name = "docreview.security.mode", havingValue = "demo")
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Seeded demo users.
     *
     * <p>Passwords come from configuration (§33), never from a literal in source. A blank value
     * fails startup rather than defaulting to something guessable — a demo environment reachable
     * with a known password is the most likely way confidential documents leak from one.
     */
    @Bean
    public InMemoryUserDetailsManager users(PasswordEncoder encoder, DemoUserProperties properties) {
        properties.validate();
        return new InMemoryUserDetailsManager(
                User.withUsername("admin")
                        .password(encoder.encode(properties.adminPassword()))
                        .roles(Role.ADMIN.name(), Role.STANDARDS_ADMIN.name(), Role.REVIEWER.name())
                        .build(),
                User.withUsername("standards")
                        .password(encoder.encode(properties.standardsAdminPassword()))
                        .roles(Role.STANDARDS_ADMIN.name(), Role.REVIEWER.name())
                        .build(),
                User.withUsername("reviewer")
                        .password(encoder.encode(properties.reviewerPassword()))
                        .roles(Role.REVIEWER.name())
                        .build());
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // Stateless API consumed by React with HTTP Basic in demo mode: there is no
                // cookie-backed form to forge, so CSRF protection has nothing to protect.
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        // Liveness and readiness must answer before authentication is available, or
                        // a failing dependency makes every container look dead.
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        // Metrics leak endpoint names and counts; scraped in-cluster, not public.
                        .requestMatchers("/actuator/**").hasRole(Role.ADMIN.name())
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // Public in BOTH modes. The SPA asks this before it has any credential, to
                        // learn whether a sign-in screen is required at all; requiring auth here
                        // would make the answer unobtainable in precisely the case it is needed.
                        .requestMatchers("/api/auth/config").permitAll()
                        // Worker callbacks (§20.3) authenticate with the internal service token, not
                        // with a user identity — there is no user behind a completion report. The
                        // token is enforced by InternalServiceTokenFilter, which runs BEFORE this
                        // chain; permitAll here would otherwise leave the path open, so the filter
                        // is what protects it and this entry only stops Spring Security demanding a
                        // second, user-shaped credential the worker cannot supply.
                        .requestMatchers("/internal/**").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(basic -> {});
        return http.build();
    }
}

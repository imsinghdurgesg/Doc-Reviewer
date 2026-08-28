package com.app.docreview.retrievalcomparison.jobs;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Requires the internal service token on {@code /internal/**}. Architecture §9.2, §3, §10.5.
 *
 * <p>Matters more here than in any other service: this is the only service holding the LiteLLM key
 * (§8.3), so an unauthenticated job dispatch is an unauthenticated route to organizational model
 * spend. §3 keeps the container off the host network, but network placement is not authentication.
 *
 * <p>A plain servlet filter rather than a Spring Security chain because §6.5 gives this service no
 * security starter, and the requirement is one header comparison on one path prefix.
 *
 * <p>Actuator health is deliberately NOT protected: probes must answer before anything else, or a
 * failing dependency makes the container look dead.
 */
@Configuration
@Slf4j
public class InternalTokenFilter {

    public static final String TOKEN_HEADER = "X-Internal-Service-Token";

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> internalTokenFilterRegistration(
            @Value("${docreview.internal.service-token:}") String expectedToken) {

        if (expectedToken == null || expectedToken.isBlank()) {
            throw new IllegalStateException(
                    "docreview.internal.service-token is not set. Refusing to start with an "
                            + "unauthenticated /internal surface in the service that holds the "
                            + "LiteLLM key. Set INTERNAL_SERVICE_TOKEN.");
        }

        var registration = new FilterRegistrationBean<OncePerRequestFilter>(
                new TokenFilter(expectedToken));
        registration.addUrlPatterns("/internal/*");
        registration.setOrder(Integer.MIN_VALUE + 10);
        return registration;
    }

    /** Compares the presented token in constant time and rejects with 401 when it does not match. */
    private static final class TokenFilter extends OncePerRequestFilter {

        private final byte[] expected;

        private TokenFilter(String expectedToken) {
            this.expected = expectedToken.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            String presented = request.getHeader(TOKEN_HEADER);

            if (presented == null || !matches(presented)) {
                // No detail in the body and no token in the log (§10.5).
                log.warn("Rejected unauthenticated internal request to {}", request.getRequestURI());
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write(
                        "{\"code\":\"INTERNAL_AUTH_REQUIRED\",\"message\":\"Invalid internal service token.\"}");
                return;
            }

            chain.doFilter(request, response);
        }

        /**
         * Constant-time comparison.
         *
         * <p>{@code String.equals} returns on the first differing byte, leaking the length of a
         * correct prefix to an attacker who can time requests.
         */
        private boolean matches(String presented) {
            return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), expected);
        }
    }
}

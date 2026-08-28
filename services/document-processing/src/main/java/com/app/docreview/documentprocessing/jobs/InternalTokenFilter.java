package com.app.docreview.documentprocessing.jobs;

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
 * Requires the internal service token on {@code /internal/**}. Architecture §9.2, §3.
 *
 * <p>§3 keeps this service off the host network, but network placement is not authentication:
 * anything that reaches the internal Docker network could otherwise dispatch jobs, and a job
 * dispatch causes LLM spend and writes artifacts. This is the check that makes the worker's own
 * trust boundary explicit rather than inherited from the network topology.
 *
 * <p>A plain servlet filter rather than a Spring Security chain because §6.4 gives this service no
 * security starter. The requirement is one header comparison on one path prefix; a filter states
 * exactly that, and adding the starter would bring an authentication model this service does not use.
 *
 * <p>Actuator health is deliberately NOT protected: probes must answer before anything else, or a
 * failing dependency makes the container look dead (mirroring review-core's §33 reasoning).
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
                            + "unauthenticated /internal surface. Set INTERNAL_SERVICE_TOKEN.");
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
                // No detail in the body and no token in the log: a rejection message that echoes
                // what was presented turns the log into a place tokens accumulate (§10.5).
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
         * <p>{@code String.equals} returns on the first differing byte, which leaks the length of a
         * correct prefix to an attacker who can time requests. {@code MessageDigest.isEqual} is the
         * JDK's constant-time comparison and is the reason this is not a one-line equals call.
         */
        private boolean matches(String presented) {
            return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), expected);
        }
    }
}

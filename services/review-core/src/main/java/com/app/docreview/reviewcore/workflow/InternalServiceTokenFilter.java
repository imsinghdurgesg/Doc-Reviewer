package com.app.docreview.reviewcore.workflow;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Requires the internal service token on review-core's {@code /internal/**} surface.
 * Architecture §9.2, §21 step 6.
 *
 * <p>Protects the callback endpoints. Without it, anything able to reach review-core could post a
 * completion callback and mark a comparison COMPLETED — fabricating a finished review, which is a
 * worse outcome than any read-only leak.
 *
 * <p>Ordered ahead of Spring Security's chain (see {@code SecurityConfig}, which permits
 * {@code /internal/**} precisely because this filter is what guards it). A worker presents a service
 * token rather than a user credential, so the two mechanisms cannot be the same one.
 *
 * <p>Registered unconditionally — NOT gated on the demo-mode property that guards
 * {@code SecurityConfig}. Tying it to a profile would mean a non-demo deployment silently ships with
 * an unauthenticated callback surface.
 */
@Configuration
@Slf4j
public class InternalServiceTokenFilter {

    public static final String TOKEN_HEADER = InternalRestClientFactory.TOKEN_HEADER;

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> internalServiceTokenFilterRegistration(
            JobDispatchProperties properties) {

        // Reuses the dispatch token: review-core and the workers are mutually authenticated with one
        // shared secret (§27 supplies a single INTERNAL_SERVICE_TOKEN). validate() already fails
        // startup when it is blank.
        properties.validate();

        var registration = new FilterRegistrationBean<OncePerRequestFilter>(
                new TokenFilter(properties.internalServiceToken()));
        registration.addUrlPatterns("/internal/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

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
                // Neither the presented value nor a hint of it goes to the log (§10.5).
                log.warn("Rejected unauthenticated internal request to {}", request.getRequestURI());
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write(
                        "{\"code\":\"INTERNAL_AUTH_REQUIRED\",\"message\":\"Invalid internal service token.\"}");
                return;
            }

            chain.doFilter(request, response);
        }

        /** Constant-time: {@code String.equals} leaks a correct prefix's length through timing. */
        private boolean matches(String presented) {
            return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), expected);
        }
    }
}

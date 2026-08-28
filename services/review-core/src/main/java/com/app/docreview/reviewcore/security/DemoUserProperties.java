package com.app.docreview.reviewcore.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Seeded demo credentials. Architecture §33.
 *
 * <p>No {@code toString()}: these are passwords, and configuration objects surface in startup logs
 * and actuator output (§34).
 */
@ConfigurationProperties(prefix = "docreview.security.demo")
public record DemoUserProperties(
        String adminPassword,
        String standardsAdminPassword,
        String reviewerPassword
) {

    /**
     * Fails fast on an unset password.
     *
     * <p>Defaulting would produce a running system with a known credential — worse than not
     * starting, because nothing signals that it happened.
     */
    public void validate() {
        require(adminPassword, "DEMO_ADMIN_PASSWORD");
        require(standardsAdminPassword, "DEMO_STANDARDS_ADMIN_PASSWORD");
        require(reviewerPassword, "DEMO_REVIEWER_PASSWORD");
    }

    private void require(String value, String envVar) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Demo security mode requires " + envVar + " to be set. Refusing to start with "
                            + "a default password.");
        }
    }
}

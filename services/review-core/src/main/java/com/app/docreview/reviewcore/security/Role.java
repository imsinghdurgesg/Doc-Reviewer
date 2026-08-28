package com.app.docreview.reviewcore.security;

/**
 * Application roles. Architecture §33.
 *
 * <p>{@code REVIEWER} can compare documents and record decisions but cannot modify the standards
 * repository — a reviewer who could edit the standard they are measured against invalidates the
 * whole exercise.
 */
public enum Role {
    REVIEWER,
    STANDARDS_ADMIN,
    ADMIN
}

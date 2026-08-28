package com.app.docreview.contracts.document;

/**
 * Which side of a comparison a document version is. Architecture §11.2.
 *
 * <p>Both go through the SAME canonical pipeline — that is the point of §11.2. The distinction
 * matters only for storage layout (§23) and for validating that a returned span belongs to the side
 * the model was asked about (§16.5).
 */
public enum DocumentType {
    STANDARD,
    UPLOADED_PROCESS
}

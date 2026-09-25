package com.abelium.inatrace.types;

/**
 * Supervisor review state of a farmer. Farmers added by collectors start PENDING; company admins
 * and system admins mark them VALIDATED or REJECTED. Farmers added by admins, and every farmer that
 * existed before this state was introduced, are VALIDATED.
 */
public enum FarmerValidationStatus {
    PENDING,
    VALIDATED,
    REJECTED
}

package com.facturaflow.catalog.service;

import java.util.Locale;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Distingue la violación de UNA restricción única de cualquier otra falla de integridad. Cubre la
 * carrera entre la comprobación previa ("¿ya existe?") y el INSERT de dos peticiones simultáneas.
 */
final class UniqueViolations {

    private UniqueViolations() {
    }

    static boolean isViolationOf(DataIntegrityViolationException exception, String constraint) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation && violation.getConstraintName() != null
                    && violation.getConstraintName().toLowerCase(Locale.ROOT).contains(constraint)) {
                return true;
            }
        }
        return false;
    }
}

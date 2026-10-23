package com.facturaflow.catalog.web;

/** 428: un PUT sin {@code If-Match}. Sin él no se puede saber si el cliente vio la última versión. */
class PreconditionRequiredException extends RuntimeException {

    PreconditionRequiredException() {
        super("Falta el header If-Match.");
    }
}

package com.facturaflow.catalog.web;

/** 400: el header {@code If-Match} no es un único ETag fuerte de este servicio (p. ej. {@code "3"}). */
class InvalidIfMatchException extends RuntimeException {

    InvalidIfMatchException() {
        super("If-Match inválido.");
    }
}

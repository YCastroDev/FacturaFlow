package com.facturaflow.catalog.service;

/** Página pedida: número desde 0 y tamaño (los límites los valida la capa web). */
public record CatalogPage(int number, int size) {

    public CatalogPage {
        if (number < 0 || size < 1) {
            throw new IllegalArgumentException("página inválida: " + number + "/" + size);
        }
    }
}

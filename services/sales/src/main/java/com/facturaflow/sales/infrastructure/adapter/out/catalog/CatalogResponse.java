package com.facturaflow.sales.infrastructure.adapter.out.catalog;

/** Respuesta HTTP de catalog sin interpretar: estado y cuerpo. */
record CatalogResponse(int status, byte[] body) {
}

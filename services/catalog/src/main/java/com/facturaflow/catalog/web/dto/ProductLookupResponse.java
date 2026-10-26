package com.facturaflow.catalog.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Los productos que existen con esos códigos, activos o no, ordenados por código. Un código "
        + "que no existe simplemente no aparece.")
public record ProductLookupResponse(List<ProductResponse> items) {
}

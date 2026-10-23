package com.facturaflow.catalog.web;

import static com.facturaflow.catalog.web.dto.RequestLimits.MAX_PAGE;
import static com.facturaflow.catalog.web.dto.RequestLimits.MAX_PAGE_SIZE;
import static com.facturaflow.catalog.web.dto.RequestLimits.MAX_SEARCH_TEXT;

import com.facturaflow.catalog.model.Product;
import com.facturaflow.catalog.service.CatalogPage;
import com.facturaflow.catalog.service.ProductService;
import com.facturaflow.catalog.web.dto.PageResponse;
import com.facturaflow.catalog.web.dto.ProductRequest;
import com.facturaflow.catalog.web.dto.ProductResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/products")
@Tag(name = "Productos", description = "Catálogo de productos: precio neto, unidad e impuestos. No se borran: se "
        + "desactivan.")
class ProductController {

    private static final String WITH_IVA = """
            { "code": "CAFE-500", "name": "Café molido 500 g", "unitPrice": 18500.00, "unit": "UNIT",
              "taxes": [ { "type": "IVA", "rate": 5 } ] }""";
    private static final String EXCLUDED = """
            { "code": "ARROZ-KG", "name": "Arroz blanco", "unitPrice": 4200, "unit": "KILOGRAM",
              "ivaExcluded": true, "taxes": [] }""";
    private static final String IVA_AND_INC = """
            { "code": "CERVEZA-330", "name": "Cerveza 330 ml", "unitPrice": 3500, "unit": "UNIT",
              "taxes": [ { "type": "IVA", "rate": 19 }, { "type": "INC", "rate": 8 } ] }""";

    private final ProductService products;

    ProductController(ProductService products) {
        this.products = products;
    }

    @PostMapping
    @Operation(summary = "Crear un producto", description = "Nace activo, con versión 0 (ETag \"0\"). El código es "
            + "único sin distinguir mayúsculas: repetirlo responde 409.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = {
            @ExampleObject(name = "Con IVA 5 %", value = WITH_IVA),
            @ExampleObject(name = "Excluido de IVA", value = EXCLUDED),
            @ExampleObject(name = "Con IVA 19 % + INC 8 %", value = IVA_AND_INC)}))
    @ApiResponse(responseCode = "201", description = "Creado", headers = {
            @Header(name = HttpHeaders.ETAG, description = ApiDocs.ETAG_HEADER, schema = @Schema(type = "string")),
            @Header(name = HttpHeaders.LOCATION, description = "URL del producto", schema = @Schema(type = "string"))})
    @ApiResponse(responseCode = "400", description = "Falta un campo o tiene un formato inválido")
    @ApiResponse(responseCode = "409", description = "Ya existe un producto con ese código")
    @ApiResponse(responseCode = "422", description = "Incumple una regla (precio, impuestos, largo...)")
    ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest request) {
        Product product = products.create(request.code(), ApiMapper.details(request));
        URI location = ServletUriComponentsBuilder.fromCurrentRequestUri().path("/{id}")
                .buildAndExpand(product.id()).toUri();
        return ResponseEntity.created(location).eTag(IfMatch.etag(product.version())).body(ApiMapper.response(product));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consultar un producto por id", description = "El header ETag de la respuesta es el que "
            + "se envía en If-Match para actualizarlo.")
    @ApiResponse(responseCode = "200", description = "El producto", headers = @Header(name = HttpHeaders.ETAG,
            description = ApiDocs.ETAG_HEADER, schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "404", description = "No existe")
    ResponseEntity<ProductResponse> get(@PathVariable UUID id) {
        return withEtag(products.get(id));
    }

    @GetMapping("/by-code/{code}")
    @Operation(summary = "Consultar un producto por código", description = "Sin distinguir mayúsculas. Si el "
            + "código tiene '/', búsquelo con el listado (q).")
    @ApiResponse(responseCode = "200", description = "El producto", headers = @Header(name = HttpHeaders.ETAG,
            description = ApiDocs.ETAG_HEADER, schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "404", description = "No existe")
    ResponseEntity<ProductResponse> getByCode(@PathVariable String code) {
        return withEtag(products.getByCode(code));
    }

    @GetMapping
    @Operation(summary = "Listar y buscar productos", description = "Ordenados por código. q busca en código y "
            + "nombre (contiene, sin distinguir mayúsculas).")
    @ApiResponse(responseCode = "200", description = "Una página de productos")
    @ApiResponse(responseCode = "400", description = "Parámetros fuera de rango")
    PageResponse<ProductResponse> list(
            @Parameter(description = "Texto a buscar (máximo 100 caracteres)", example = "café")
            @RequestParam(required = false) @Size(max = MAX_SEARCH_TEXT, message = "es demasiado largo") String q,
            @Parameter(description = ApiDocs.ACTIVE) @RequestParam(required = false) Boolean active,
            @Parameter(description = ApiDocs.PAGE) @RequestParam(defaultValue = "0")
            @Min(value = 0, message = "debe ser 0 o mayor") @Max(value = MAX_PAGE, message = "es demasiado grande")
            int page,
            @Parameter(description = ApiDocs.SIZE) @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "debe ser 1 o mayor")
            @Max(value = MAX_PAGE_SIZE, message = "admite máximo " + MAX_PAGE_SIZE) int size) {
        return ApiMapper.page(products.search(q, active, new CatalogPage(page, size)), ApiMapper::response);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Actualizar un producto (requiere If-Match)", description = """
            Reemplazo completo: envíe todos los datos. El código debe ser el mismo (no se puede cambiar: 422).

            **Cómo probarlo en Swagger UI:**
            1. Ejecute `GET /api/v1/products/{id}` y copie el header `etag` de la respuesta, p. ej. `"0"`.
            2. Aquí, escriba ese valor (con comillas) en **If-Match** y el cuerpo completo.
            3. La respuesta trae el ETag nuevo (`"1"`): úselo para el siguiente cambio.

            Con un ETag viejo responde 412 (otra persona lo cambió: vuelva a consultarlo). Un PUT sin cambios no \
            sube la versión.""")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples =
            @ExampleObject(name = "Cambiar precio e IVA", value = """
                    { "code": "CAFE-500", "name": "Café molido 500 g", "unitPrice": 19900.00, "unit": "UNIT",
                      "taxes": [ { "type": "IVA", "rate": 19 } ] }""")))
    @ApiResponse(responseCode = "200", description = "Actualizado", headers = @Header(name = HttpHeaders.ETAG,
            description = ApiDocs.ETAG_HEADER, schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "400", description = "Petición o If-Match con formato inválido")
    @ApiResponse(responseCode = "404", description = "No existe")
    @ApiResponse(responseCode = "412", description = "El ETag de If-Match ya no es la versión actual")
    @ApiResponse(responseCode = "422", description = "Incumple una regla, o intenta cambiar el código")
    @ApiResponse(responseCode = "428", description = "Falta el header If-Match")
    ResponseEntity<ProductResponse> update(
            @PathVariable UUID id,
            @Parameter(in = ParameterIn.HEADER, name = HttpHeaders.IF_MATCH, required = true,
                    description = ApiDocs.IF_MATCH_PUT,
                    schema = @Schema(type = "string", example = ApiDocs.IF_MATCH_EXAMPLE))
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ProductRequest request) {
        long expectedVersion = IfMatch.required(ifMatch);
        return withEtag(products.update(id, expectedVersion, request.code(), ApiMapper.details(request)));
    }

    @PostMapping("/{id}/deactivate")
    @Operation(summary = "Desactivar un producto", description = "No se borra (ventas y facturas lo referencian). "
            + "Idempotente: desactivar uno inactivo no cambia nada.")
    @ApiResponse(responseCode = "200", description = "Inactivo", headers = @Header(name = HttpHeaders.ETAG,
            description = ApiDocs.ETAG_HEADER, schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "404", description = "No existe")
    @ApiResponse(responseCode = "409", description = "Otra petición lo modificó al mismo tiempo")
    @ApiResponse(responseCode = "412", description = "If-Match enviado y ya no es la versión actual")
    ResponseEntity<ProductResponse> deactivate(
            @PathVariable UUID id,
            @Parameter(in = ParameterIn.HEADER, name = HttpHeaders.IF_MATCH, description = ApiDocs.IF_MATCH_OPTIONAL)
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return withEtag(products.deactivate(id, IfMatch.optional(ifMatch)));
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "Reactivar un producto", description = "Idempotente: activar uno activo no cambia nada.")
    @ApiResponse(responseCode = "200", description = "Activo", headers = @Header(name = HttpHeaders.ETAG,
            description = ApiDocs.ETAG_HEADER, schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "404", description = "No existe")
    @ApiResponse(responseCode = "409", description = "Otra petición lo modificó al mismo tiempo")
    @ApiResponse(responseCode = "412", description = "If-Match enviado y ya no es la versión actual")
    ResponseEntity<ProductResponse> activate(
            @PathVariable UUID id,
            @Parameter(in = ParameterIn.HEADER, name = HttpHeaders.IF_MATCH, description = ApiDocs.IF_MATCH_OPTIONAL)
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return withEtag(products.activate(id, IfMatch.optional(ifMatch)));
    }

    private static ResponseEntity<ProductResponse> withEtag(Product product) {
        return ResponseEntity.status(HttpStatus.OK).eTag(IfMatch.etag(product.version()))
                .body(ApiMapper.response(product));
    }
}

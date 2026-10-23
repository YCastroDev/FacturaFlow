package com.facturaflow.catalog.web;

import static com.facturaflow.catalog.web.dto.RequestLimits.MAX_PAGE;
import static com.facturaflow.catalog.web.dto.RequestLimits.MAX_PAGE_SIZE;
import static com.facturaflow.catalog.web.dto.RequestLimits.MAX_SEARCH_TEXT;

import com.facturaflow.catalog.model.Customer;
import com.facturaflow.catalog.service.CatalogPage;
import com.facturaflow.catalog.service.CustomerService;
import com.facturaflow.catalog.web.dto.ApiDocumentType;
import com.facturaflow.catalog.web.dto.CustomerRequest;
import com.facturaflow.catalog.web.dto.CustomerResponse;
import com.facturaflow.catalog.web.dto.PageResponse;
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
@RequestMapping("/api/v1/customers")
@Tag(name = "Clientes", description = "Clientes con sus datos fiscales (los mismos que pide una factura). Únicos por "
        + "tipo + número de documento. No se borran: se desactivan.")
class CustomerController {

    private static final String LEGAL_ENTITY = """
            { "documentType": "NIT", "documentNumber": "890903938", "checkDigit": 8,
              "personType": "LEGAL_ENTITY", "companyName": "Cliente Ejemplo S.A.S.",
              "taxScheme": "IVA", "fiscalResponsibilities": [ "LARGE_TAXPAYER", "SELF_WITHHOLDER" ],
              "municipalityCode": "05001", "address": "Carrera 48 # 10-45", "phone": "+57 604 444 5555",
              "email": "facturacion@cliente.example" }""";
    private static final String NATURAL_PERSON = """
            { "documentType": "CITIZENSHIP_CARD", "documentNumber": "1020304050",
              "personType": "NATURAL_PERSON", "names": "Ana María Pérez",
              "taxScheme": "NOT_APPLICABLE", "fiscalResponsibilities": [ "NOT_RESPONSIBLE" ],
              "municipalityCode": "11001", "email": "ana.perez@correo.example" }""";

    private final CustomerService customers;

    CustomerController(CustomerService customers) {
        this.customers = customers;
    }

    @PostMapping
    @Operation(summary = "Crear un cliente", description = "Nace activo, con versión 0 (ETag \"0\"). Repetir tipo + "
            + "número de documento responde 409.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = {
            @ExampleObject(name = "Persona jurídica con NIT", value = LEGAL_ENTITY),
            @ExampleObject(name = "Persona natural con cédula", value = NATURAL_PERSON)}))
    @ApiResponse(responseCode = "201", description = "Creado", headers = {
            @Header(name = HttpHeaders.ETAG, description = ApiDocs.ETAG_HEADER, schema = @Schema(type = "string")),
            @Header(name = HttpHeaders.LOCATION, description = "URL del cliente", schema = @Schema(type = "string"))})
    @ApiResponse(responseCode = "400", description = "Falta un campo o tiene un formato inválido")
    @ApiResponse(responseCode = "409", description = "Ya existe un cliente con ese documento")
    @ApiResponse(responseCode = "422", description = "Incumple una regla (DV del NIT, tipo de persona, municipio...)")
    ResponseEntity<CustomerResponse> create(@Valid @RequestBody CustomerRequest request) {
        Customer customer = customers.create(ApiMapper.document(request), ApiMapper.profile(request));
        URI location = ServletUriComponentsBuilder.fromCurrentRequestUri().path("/{id}")
                .buildAndExpand(customer.id()).toUri();
        return ResponseEntity.created(location).eTag(IfMatch.etag(customer.version()))
                .body(ApiMapper.response(customer));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consultar un cliente por id", description = "El header ETag de la respuesta es el que se "
            + "envía en If-Match para actualizarlo.")
    @ApiResponse(responseCode = "200", description = "El cliente", headers = @Header(name = HttpHeaders.ETAG,
            description = ApiDocs.ETAG_HEADER, schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "404", description = "No existe")
    ResponseEntity<CustomerResponse> get(@PathVariable UUID id) {
        return withEtag(customers.get(id));
    }

    @GetMapping("/by-document/{documentType}/{documentNumber}")
    @Operation(summary = "Consultar un cliente por documento", description = "Tipo + número (sin el DV). El número "
            + "no distingue mayúsculas.")
    @ApiResponse(responseCode = "200", description = "El cliente", headers = @Header(name = HttpHeaders.ETAG,
            description = ApiDocs.ETAG_HEADER, schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "400", description = "Tipo de documento desconocido")
    @ApiResponse(responseCode = "404", description = "No existe")
    ResponseEntity<CustomerResponse> getByDocument(
            @Parameter(example = "NIT") @PathVariable ApiDocumentType documentType,
            @Parameter(example = "890903938") @PathVariable String documentNumber) {
        return withEtag(customers.getByDocument(ApiMapper.documentType(documentType), documentNumber));
    }

    @GetMapping
    @Operation(summary = "Listar y buscar clientes", description = "Ordenados por nombre visible (razón social o "
            + "nombres). q busca en número de documento, razón social, nombres y email (contiene, sin distinguir "
            + "mayúsculas).")
    @ApiResponse(responseCode = "200", description = "Una página de clientes")
    @ApiResponse(responseCode = "400", description = "Parámetros fuera de rango")
    PageResponse<CustomerResponse> list(
            @Parameter(description = "Texto a buscar (máximo 100 caracteres)", example = "ejemplo")
            @RequestParam(required = false) @Size(max = MAX_SEARCH_TEXT, message = "es demasiado largo") String q,
            @Parameter(description = ApiDocs.ACTIVE) @RequestParam(required = false) Boolean active,
            @Parameter(description = ApiDocs.PAGE) @RequestParam(defaultValue = "0")
            @Min(value = 0, message = "debe ser 0 o mayor") @Max(value = MAX_PAGE, message = "es demasiado grande")
            int page,
            @Parameter(description = ApiDocs.SIZE) @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "debe ser 1 o mayor")
            @Max(value = MAX_PAGE_SIZE, message = "admite máximo " + MAX_PAGE_SIZE) int size) {
        return ApiMapper.page(customers.search(q, active, new CatalogPage(page, size)), ApiMapper::response);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Actualizar un cliente (requiere If-Match)", description = """
            Reemplazo completo: envíe todos los datos. El documento (tipo, número y DV) debe ser el mismo (no se \
            puede cambiar: 422).

            **Cómo probarlo en Swagger UI:**
            1. Ejecute `GET /api/v1/customers/{id}` y copie el header `etag` de la respuesta, p. ej. `"0"`.
            2. Aquí, escriba ese valor (con comillas) en **If-Match** y el cuerpo completo.
            3. La respuesta trae el ETag nuevo (`"1"`): úselo para el siguiente cambio.

            Con un ETag viejo responde 412 (otra persona lo cambió: vuelva a consultarlo). Un PUT sin cambios no \
            sube la versión.""")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples =
            @ExampleObject(name = "Cambiar dirección y responsabilidades", value = """
                    { "documentType": "NIT", "documentNumber": "890903938", "checkDigit": 8,
                      "personType": "LEGAL_ENTITY", "companyName": "Cliente Ejemplo S.A.S.",
                      "taxScheme": "IVA", "fiscalResponsibilities": [ "LARGE_TAXPAYER" ],
                      "municipalityCode": "11001", "address": "Calle 100 # 15-20", "phone": "+57 601 555 1234",
                      "email": "facturacion@cliente.example" }""")))
    @ApiResponse(responseCode = "200", description = "Actualizado", headers = @Header(name = HttpHeaders.ETAG,
            description = ApiDocs.ETAG_HEADER, schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "400", description = "Petición o If-Match con formato inválido")
    @ApiResponse(responseCode = "404", description = "No existe")
    @ApiResponse(responseCode = "412", description = "El ETag de If-Match ya no es la versión actual")
    @ApiResponse(responseCode = "422", description = "Incumple una regla, o intenta cambiar el documento")
    @ApiResponse(responseCode = "428", description = "Falta el header If-Match")
    ResponseEntity<CustomerResponse> update(
            @PathVariable UUID id,
            @Parameter(in = ParameterIn.HEADER, name = HttpHeaders.IF_MATCH, required = true,
                    description = ApiDocs.IF_MATCH_PUT,
                    schema = @Schema(type = "string", example = ApiDocs.IF_MATCH_EXAMPLE))
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody CustomerRequest request) {
        long expectedVersion = IfMatch.required(ifMatch);
        return withEtag(customers.update(id, expectedVersion, ApiMapper.document(request), ApiMapper.profile(request)));
    }

    @PostMapping("/{id}/deactivate")
    @Operation(summary = "Desactivar un cliente", description = "No se borra (ventas y facturas lo referencian). "
            + "Idempotente: desactivar uno inactivo no cambia nada.")
    @ApiResponse(responseCode = "200", description = "Inactivo", headers = @Header(name = HttpHeaders.ETAG,
            description = ApiDocs.ETAG_HEADER, schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "404", description = "No existe")
    @ApiResponse(responseCode = "409", description = "Otra petición lo modificó al mismo tiempo")
    @ApiResponse(responseCode = "412", description = "If-Match enviado y ya no es la versión actual")
    ResponseEntity<CustomerResponse> deactivate(
            @PathVariable UUID id,
            @Parameter(in = ParameterIn.HEADER, name = HttpHeaders.IF_MATCH, description = ApiDocs.IF_MATCH_OPTIONAL)
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return withEtag(customers.deactivate(id, IfMatch.optional(ifMatch)));
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "Reactivar un cliente", description = "Idempotente: activar uno activo no cambia nada.")
    @ApiResponse(responseCode = "200", description = "Activo", headers = @Header(name = HttpHeaders.ETAG,
            description = ApiDocs.ETAG_HEADER, schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "404", description = "No existe")
    @ApiResponse(responseCode = "409", description = "Otra petición lo modificó al mismo tiempo")
    @ApiResponse(responseCode = "412", description = "If-Match enviado y ya no es la versión actual")
    ResponseEntity<CustomerResponse> activate(
            @PathVariable UUID id,
            @Parameter(in = ParameterIn.HEADER, name = HttpHeaders.IF_MATCH, description = ApiDocs.IF_MATCH_OPTIONAL)
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return withEtag(customers.activate(id, IfMatch.optional(ifMatch)));
    }

    private static ResponseEntity<CustomerResponse> withEtag(Customer customer) {
        return ResponseEntity.status(HttpStatus.OK).eTag(IfMatch.etag(customer.version()))
                .body(ApiMapper.response(customer));
    }
}

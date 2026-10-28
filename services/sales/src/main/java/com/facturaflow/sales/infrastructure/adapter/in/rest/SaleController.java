package com.facturaflow.sales.infrastructure.adapter.in.rest;

import static com.facturaflow.sales.infrastructure.adapter.in.rest.dto.RequestLimits.MAX_PAGE;
import static com.facturaflow.sales.infrastructure.adapter.in.rest.dto.RequestLimits.MAX_PAGE_SIZE;

import com.facturaflow.sales.application.port.in.CreateSaleResult;
import com.facturaflow.sales.application.port.in.CreateSaleUseCase;
import com.facturaflow.sales.application.port.in.GetSaleUseCase;
import com.facturaflow.sales.application.port.in.ListSalesQuery;
import com.facturaflow.sales.application.port.in.ListSalesUseCase;
import com.facturaflow.sales.domain.model.SaleId;
import com.facturaflow.sales.domain.model.SaleStatus;
import com.facturaflow.sales.infrastructure.adapter.in.rest.dto.ApiSaleStatus;
import com.facturaflow.sales.infrastructure.adapter.in.rest.dto.CreateSaleRequest;
import com.facturaflow.sales.infrastructure.adapter.in.rest.dto.PageResponse;
import com.facturaflow.sales.infrastructure.adapter.in.rest.dto.SaleResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/sales")
@Tag(name = "Ventas", description = "Ventas confirmadas con clientes y productos de catalog")
class SaleController {

    /** Usa los datos que siembra scripts/seed-catalog.sh. */
    private static final String CASH_EXAMPLE = """
            { "referenceCode": "VENTA-2026-0001",
              "customer": { "documentType": "NIT", "documentNumber": "890903938" },
              "lines": [ { "productCode": "CAFE-500", "quantity": 2, "discountRate": 10 },
                         { "productCode": "ARROZ-KG", "quantity": 1.5 } ],
              "paymentMethod": "CASH", "paymentTerms": { "type": "CASH" },
              "observation": "Entregar en portería" }""";
    private static final String CREDIT_EXAMPLE = """
            { "referenceCode": "VENTA-2026-0002",
              "customer": { "documentType": "CITIZENSHIP_CARD", "documentNumber": "1020304050" },
              "lines": [ { "productCode": "CERVEZA-330", "quantity": 12 },
                         { "productCode": "CAFE-500", "quantity": 1 } ],
              "paymentMethod": "BANK_TRANSFER", "paymentTerms": { "type": "CREDIT", "dueDate": "2030-01-31" } }""";
    private static final String INACTIVE_EXAMPLE = """
            { "referenceCode": "VENTA-2026-0003",
              "customer": { "documentType": "NIT", "documentNumber": "890903938" },
              "lines": [ { "productCode": "DEMO-CAFE", "quantity": 1 }, { "productCode": "NO-EXISTE", "quantity": 1 } ],
              "paymentMethod": "CASH", "paymentTerms": { "type": "CASH" } }""";

    private static final String PREFERRED_USERNAME = "preferred_username";

    private final CreateSaleUseCase createSale;
    private final GetSaleUseCase getSale;
    private final ListSalesUseCase listSales;

    SaleController(CreateSaleUseCase createSale, GetSaleUseCase getSale, ListSalesUseCase listSales) {
        this.createSale = createSale;
        this.getSale = getSale;
        this.listSales = listSales;
    }

    @PostMapping
    @Operation(summary = "Confirmar una venta", description = """
            La venta se crea completa y queda CONFIRMED. Cliente, precios, nombres e impuestos salen SIEMPRE de \
            catalog (la petición solo trae documento, códigos, cantidades, descuentos y pago) y la venta guarda \
            una copia de ellos.

            **Idempotente** por `referenceCode`: repetir la misma petición devuelve la venta existente (200), \
            aunque catalog esté caído o un precio haya cambiado; con otro contenido, 409.

            **Quién la hizo** (`soldBy`) sale del token (`sub` y `preferred_username`), nunca del cuerpo. No entra en             la idempotencia: si otro usuario repite la petición, recibe la venta existente con su vendedor original.

            Los ejemplos usan los datos de `scripts/seed-catalog.sh`.""")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = {
            @ExampleObject(name = "Contado (NIT, café con descuento y arroz excluido)", value = CASH_EXAMPLE),
            @ExampleObject(name = "Crédito (cédula, cerveza con IVA + INC)", value = CREDIT_EXAMPLE),
            @ExampleObject(name = "422: producto inactivo e inexistente", value = INACTIVE_EXAMPLE)}))
    @ApiResponse(responseCode = "201", description = "Venta confirmada", headers = @Header(name = HttpHeaders.LOCATION,
            description = "URL de la venta", schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "200", description = "Reintento idéntico: la venta ya existía")
    @ApiResponse(responseCode = "400", description = "Falta un campo o tiene un formato inválido")
    @ApiResponse(responseCode = "409", description = "El referenceCode ya existe con un contenido distinto")
    @ApiResponse(responseCode = "422", description = "Cliente o productos inexistentes o inactivos en catalog, o una "
            + "regla incumplida (cantidad, descuento, vencimiento...)")
    @ApiResponse(responseCode = "503", description = "Catalog no responde: la venta no se creó; reintente después de "
            + "Retry-After", headers = @Header(name = HttpHeaders.RETRY_AFTER, schema = @Schema(type = "integer")))
    ResponseEntity<SaleResponse> create(@Valid @RequestBody CreateSaleRequest request,
                                        @AuthenticationPrincipal Jwt token) {
        CreateSaleResult result = createSale.create(ApiMapper.command(request, subject(token), username(token)));
        SaleResponse body = ApiMapper.response(result.sale());
        if (!result.created()) {
            return ResponseEntity.ok(body);
        }
        URI location = ServletUriComponentsBuilder.fromCurrentRequestUri().path("/{id}")
                .buildAndExpand(body.id()).toUri();
        return ResponseEntity.created(location).body(body);
    }

    /**
     * El {@code sub} identifica al usuario. Un token de nuestro realm siempre lo trae (scope {@code basic}): si falta,
     * es un error de configuración de Keycloak, no del vendedor, y responde 500 con un log ERROR.
     */
    private static String subject(Jwt token) {
        String subject = token.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new IllegalStateException("El token no trae el claim 'sub': revisar el realm (scope basic)");
        }
        return subject;
    }

    /** {@code preferred_username} para mostrar; sin él (scope {@code profile} ausente), el {@code sub}. */
    private static String username(Jwt token) {
        String username = token.getClaimAsString(PREFERRED_USERNAME);
        return username == null || username.isBlank() ? token.getSubject() : username;
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consultar una venta por id")
    @ApiResponse(responseCode = "200", description = "La venta")
    @ApiResponse(responseCode = "404", description = "No existe")
    SaleResponse get(@PathVariable UUID id) {
        return ApiMapper.response(getSale.byId(new SaleId(id)));
    }

    @GetMapping("/by-reference/{referenceCode}")
    @Operation(summary = "Consultar una venta por su código de referencia")
    @ApiResponse(responseCode = "200", description = "La venta")
    @ApiResponse(responseCode = "404", description = "No existe")
    SaleResponse getByReference(@Parameter(example = "VENTA-2026-0001") @PathVariable String referenceCode) {
        return ApiMapper.response(getSale.byReferenceCode(referenceCode));
    }

    @GetMapping
    @Operation(summary = "Listar ventas", description = "De la más reciente a la más antigua, con el mismo formato de "
            + "página que catalog.")
    @ApiResponse(responseCode = "200", description = "Una página de ventas")
    @ApiResponse(responseCode = "400", description = "Parámetros fuera de rango")
    PageResponse<SaleResponse> list(
            @Parameter(description = "Estado (opcional; sin él, todas)") @RequestParam(required = false)
            ApiSaleStatus status,
            @Parameter(description = "Número de página, desde 0 (máximo 10000)") @RequestParam(defaultValue = "0")
            @Min(value = 0, message = "debe ser 0 o mayor") @Max(value = MAX_PAGE, message = "es demasiado grande")
            int page,
            @Parameter(description = "Registros por página, de 1 a 100 (fuera de rango: 400)")
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "debe ser 1 o mayor")
            @Max(value = MAX_PAGE_SIZE, message = "admite máximo " + MAX_PAGE_SIZE) int size) {
        return ApiMapper.page(listSales.list(new ListSalesQuery(ApiMapper.convert(status, SaleStatus.class), page,
                size)));
    }
}

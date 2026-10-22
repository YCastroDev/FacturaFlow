package com.facturaflow.invoicing.infrastructure.adapter.in.rest;

import com.facturaflow.invoicing.application.port.in.CreateInvoiceResult;
import com.facturaflow.invoicing.application.port.in.CreateInvoiceUseCase;
import com.facturaflow.invoicing.application.port.in.GetInvoicePdfUseCase;
import com.facturaflow.invoicing.application.port.in.GetInvoiceUseCase;
import com.facturaflow.invoicing.application.port.in.IssueInvoiceCommand;
import com.facturaflow.invoicing.application.port.in.IssueInvoiceUseCase;
import com.facturaflow.invoicing.application.port.out.InvoicePdf;
import com.facturaflow.invoicing.infrastructure.adapter.in.rest.dto.CreateInvoiceRequest;
import com.facturaflow.invoicing.infrastructure.adapter.in.rest.dto.InvoiceResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Adaptador de entrada HTTP. Solo traduce: DTO → comando → caso de uso → DTO. No contiene
 * reglas de negocio ni conoce repositorios o proveedores; los errores los traduce
 * {@link ProblemDetailsHandler}.
 */
@RestController
@RequestMapping("/api/v1/invoices")
@Tag(name = "Facturas", description = "Crear, consultar y emitir facturas electrónicas")
class InvoiceController {

    private final CreateInvoiceUseCase createInvoice;
    private final GetInvoiceUseCase getInvoice;
    private final IssueInvoiceUseCase issueInvoice;
    private final GetInvoicePdfUseCase getInvoicePdf;

    InvoiceController(CreateInvoiceUseCase createInvoice, GetInvoiceUseCase getInvoice,
                      IssueInvoiceUseCase issueInvoice, GetInvoicePdfUseCase getInvoicePdf) {
        this.createInvoice = createInvoice;
        this.getInvoice = getInvoice;
        this.issueInvoice = issueInvoice;
        this.getInvoicePdf = getInvoicePdf;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Crear una factura PENDING",
            description = "Idempotente por referenceCode: repetir la misma petición devuelve la factura existente "
                    + "(200); el mismo código con otro contenido responde 409. La fecha de emisión la pone el "
                    + "servidor (fecha de Colombia).",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = CreateInvoiceRequest.class),
                    examples = {
                            @ExampleObject(name = "Contado con IVA", value = ApiExamples.CASH_WITH_IVA),
                            @ExampleObject(name = "Crédito con IVA + INC", value = ApiExamples.CREDIT_WITH_IVA_AND_INC)
                    })))
    @ApiResponse(responseCode = "201", description = "Factura creada; header Location con su URL")
    @ApiResponse(responseCode = "200", description = "Reintento idéntico: se devuelve la factura existente")
    @ApiResponse(responseCode = "400", description = "Forma inválida (campos faltantes, JSON ilegible, campos desconocidos)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "El referenceCode ya existe con un contenido distinto",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "Viola una regla de negocio (p. ej. dígito de verificación)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<InvoiceResponse> create(@Valid @RequestBody CreateInvoiceRequest request) {
        CreateInvoiceResult result = createInvoice.create(InvoiceRestMapper.toCommand(request));
        InvoiceResponse body = InvoiceRestMapper.toResponse(result.invoice());
        if (!result.created()) {
            return ResponseEntity.ok(body);
        }
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(body.id()).toUri();
        return ResponseEntity.created(location).body(body);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consultar una factura por id")
    @ApiResponse(responseCode = "200", description = "La factura")
    @ApiResponse(responseCode = "400", description = "El id no es un UUID",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No existe",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    InvoiceResponse getById(@PathVariable UUID id) {
        return InvoiceRestMapper.toResponse(getInvoice.byId(id));
    }

    @GetMapping
    @Operation(summary = "Consultar una factura por el código de referencia de su venta",
            description = "El código es único: devuelve una factura (no una lista) o 404.")
    @ApiResponse(responseCode = "200", description = "La factura")
    @ApiResponse(responseCode = "400", description = "Falta el parámetro referenceCode",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No existe",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    InvoiceResponse getByReferenceCode(
            @Parameter(description = "Código de referencia de la venta", example = "VENTA-2026-0001")
            @RequestParam String referenceCode) {
        return InvoiceRestMapper.toResponse(getInvoice.byReferenceCode(referenceCode));
    }

    @PostMapping("/{id}/issue")
    @Operation(summary = "Emitir una factura ante la DIAN",
            description = "PENDING → ISSUED o REJECTED (un rechazo es un resultado válido: 200 con la factura "
                    + "rechazada y su motivo). Si ya está ISSUED se devuelve sin volver a llamar al proveedor. "
                    + "Con el proveedor falso (invoicing.provider=fake): una observación con SIMULAR_RECHAZO "
                    + "produce un rechazo y una con SIMULAR_CAIDA produce 503 (la factura sigue PENDING y se "
                    + "puede reintentar).")
    @ApiResponse(responseCode = "200", description = "Factura ISSUED o REJECTED")
    @ApiResponse(responseCode = "404", description = "No existe",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Ya fue rechazada, o una emisión simultánea terminó distinto",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Proveedor no disponible; reintentar (header Retry-After)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    InvoiceResponse issue(@PathVariable UUID id) {
        return InvoiceRestMapper.toResponse(issueInvoice.issue(new IssueInvoiceCommand(id)));
    }

    @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary = "Descargar la representación gráfica (PDF) de una factura emitida",
            description = "El PDF se pide al proveedor en cada descarga (no se guarda). Con el proveedor falso es "
                    + "un PDF mínimo marcado como DOCUMENTO FALSO.")
    @ApiResponse(responseCode = "200", description = "El PDF, como archivo adjunto",
            content = @Content(mediaType = MediaType.APPLICATION_PDF_VALUE,
                    schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "404", description = "No existe",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "La factura no está ISSUED",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Proveedor no disponible; reintentar (header Retry-After)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<byte[]> pdf(@PathVariable UUID id) {
        InvoicePdf pdf = getInvoicePdf.pdf(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(pdf.fileName()).build().toString())
                .body(pdf.content());
    }
}

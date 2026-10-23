package com.facturaflow.invoicing.infrastructure.adapter.in.rest;

import com.facturaflow.invoicing.application.port.in.CreateCreditNoteResult;
import com.facturaflow.invoicing.application.port.in.CreateCreditNoteUseCase;
import com.facturaflow.invoicing.application.port.in.GetCreditNotePdfUseCase;
import com.facturaflow.invoicing.application.port.in.GetCreditNoteUseCase;
import com.facturaflow.invoicing.application.port.in.IssueCreditNoteCommand;
import com.facturaflow.invoicing.application.port.in.IssueCreditNoteUseCase;
import com.facturaflow.invoicing.application.port.out.InvoicePdf;
import com.facturaflow.invoicing.infrastructure.adapter.in.rest.dto.CreateCreditNoteRequest;
import com.facturaflow.invoicing.infrastructure.adapter.in.rest.dto.CreditNoteResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Adaptador de entrada HTTP de las notas crédito (ADR 0009). Como {@link InvoiceController}, solo
 * traduce; los errores los traduce {@link ProblemDetailsHandler}. Las notas se crean y se listan
 * bajo su factura, y se consultan, emiten y descargan por su propio id.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Notas crédito", description = "Corregir facturas emitidas: devolución parcial o anulación total")
class CreditNoteController {

    private final CreateCreditNoteUseCase createCreditNote;
    private final GetCreditNoteUseCase getCreditNote;
    private final IssueCreditNoteUseCase issueCreditNote;
    private final GetCreditNotePdfUseCase getCreditNotePdf;

    CreditNoteController(CreateCreditNoteUseCase createCreditNote, GetCreditNoteUseCase getCreditNote,
                         IssueCreditNoteUseCase issueCreditNote, GetCreditNotePdfUseCase getCreditNotePdf) {
        this.createCreditNote = createCreditNote;
        this.getCreditNote = getCreditNote;
        this.issueCreditNote = issueCreditNote;
        this.getCreditNotePdf = getCreditNotePdf;
    }

    @PostMapping(value = "/invoices/{invoiceId}/credit-notes", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Crear una nota crédito PENDING sobre una factura ISSUED",
            description = "Lo acreditado de cada línea (sumando las notas PENDING e ISSUED) nunca puede superar lo "
                    + "facturado: si no cabe, responde 422. El cliente, los precios y los impuestos salen de la "
                    + "factura. Idempotente por referenceCode: repetir la misma petición devuelve la nota existente "
                    + "(200); el mismo código con otro contenido responde 409.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = CreateCreditNoteRequest.class),
                    examples = {
                            @ExampleObject(name = "Devolución parcial", value = ApiExamples.PARTIAL_RETURN),
                            @ExampleObject(name = "Anulación total", value = ApiExamples.TOTAL_ANNULMENT)
                    })))
    @ApiResponse(responseCode = "201", description = "Nota creada; header Location con su URL")
    @ApiResponse(responseCode = "200", description = "Reintento idéntico: se devuelve la nota existente")
    @ApiResponse(responseCode = "400", description = "Forma inválida (campos faltantes, JSON ilegible, campos desconocidos)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "La factura no existe",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "La factura no está ISSUED, el referenceCode ya existe con otro "
            + "contenido, o hubo demasiadas notas simultáneas sobre la misma factura",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "Viola una regla de negocio (p. ej. acreditar más de lo facturado)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<CreditNoteResponse> create(@PathVariable UUID invoiceId,
                                              @Valid @RequestBody CreateCreditNoteRequest request) {
        CreateCreditNoteResult result = createCreditNote.create(CreditNoteRestMapper.toCommand(invoiceId, request));
        CreditNoteResponse body = CreditNoteRestMapper.toResponse(result.creditNote());
        if (!result.created()) {
            return ResponseEntity.ok(body);
        }
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/credit-notes/{id}").buildAndExpand(body.id()).toUri();
        return ResponseEntity.created(location).body(body);
    }

    @GetMapping("/invoices/{invoiceId}/credit-notes")
    @Operation(summary = "Listar las notas crédito de una factura",
            description = "Todas, en cualquier estado, en orden de creación. Lista vacía si no tiene.")
    @ApiResponse(responseCode = "200", description = "Las notas de la factura")
    @ApiResponse(responseCode = "404", description = "La factura no existe",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    List<CreditNoteResponse> listByInvoice(@PathVariable UUID invoiceId) {
        return getCreditNote.byInvoice(invoiceId).stream().map(CreditNoteRestMapper::toResponse).toList();
    }

    @GetMapping("/credit-notes/{id}")
    @Operation(summary = "Consultar una nota crédito por id")
    @ApiResponse(responseCode = "200", description = "La nota")
    @ApiResponse(responseCode = "400", description = "El id no es un UUID",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No existe",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    CreditNoteResponse getById(@PathVariable UUID id) {
        return CreditNoteRestMapper.toResponse(getCreditNote.byId(id));
    }

    @PostMapping("/credit-notes/{id}/issue")
    @Operation(summary = "Emitir una nota crédito ante la DIAN",
            description = "PENDING → ISSUED o REJECTED (un rechazo es un resultado válido: 200 con la nota rechazada "
                    + "y su motivo; sus cantidades quedan libres). Si ya está ISSUED se devuelve sin volver a llamar "
                    + "al proveedor. Con el proveedor falso: una observación con SIMULAR_RECHAZO produce un rechazo y "
                    + "una con SIMULAR_CAIDA produce 503 (la nota sigue PENDING y se puede reintentar).")
    @ApiResponse(responseCode = "200", description = "Nota ISSUED o REJECTED")
    @ApiResponse(responseCode = "404", description = "No existe",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Ya fue rechazada, o una emisión simultánea terminó distinto",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Proveedor no disponible; reintentar (header Retry-After)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    CreditNoteResponse issue(@PathVariable UUID id) {
        return CreditNoteRestMapper.toResponse(issueCreditNote.issue(new IssueCreditNoteCommand(id)));
    }

    @GetMapping(value = "/credit-notes/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary = "Descargar la representación gráfica (PDF) de una nota crédito emitida",
            description = "El PDF se pide al proveedor en cada descarga (no se guarda). Con el proveedor falso es "
                    + "un PDF mínimo marcado como DOCUMENTO FALSO.")
    @ApiResponse(responseCode = "200", description = "El PDF, como archivo adjunto",
            content = @Content(mediaType = MediaType.APPLICATION_PDF_VALUE,
                    schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "404", description = "No existe",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "La nota no está ISSUED",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Proveedor no disponible; reintentar (header Retry-After)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<byte[]> pdf(@PathVariable UUID id) {
        InvoicePdf pdf = getCreditNotePdf.pdf(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(pdf.fileName()).build().toString())
                .body(pdf.content());
    }
}

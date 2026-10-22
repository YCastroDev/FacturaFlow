package com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus;

import com.facturaflow.invoicing.application.port.out.InvoicePdf;
import com.facturaflow.invoicing.application.port.out.IssuanceResult;
import com.facturaflow.invoicing.domain.exception.DomainException;
import com.facturaflow.invoicing.domain.model.ElectronicDocument;
import com.facturaflow.invoicing.domain.model.IdentificationDocument;
import com.facturaflow.invoicing.domain.model.Invoice;
import com.facturaflow.invoicing.domain.model.Money;
import com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusDtos.BillData;
import com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusDtos.BillResponse;
import com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusDtos.PdfData;
import com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusDtos.PdfResponse;
import com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusDtos.ResponseCustomer;
import com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusDtos.ValidationErrorResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Decide qué significa cada respuesta de Factus (tabla del ADR 0008). Respuestas de negocio:
 * <ul>
 *   <li>200/201 con {@code is_validated: true} y que corresponde a nuestra factura → {@code Issued}.
 *       {@code data.errors} trae entonces <i>notificaciones</i> de la DIAN, no errores: se registran.</li>
 *   <li>422 por los datos de la factura → {@code Rejected}, con los mensajes de Factus como motivo.</li>
 * </ul>
 * Todo lo demás es una {@link FactusTechnicalException} y la factura sigue PENDING: {@code is_validated:
 * false}, 409 (pendiente en la DIAN), 401 tras renovar el token, 429, 5xx, otros códigos, cuerpos
 * ilegibles, un 422 por la configuración del adaptador y una respuesta que no corresponde a la
 * factura. Nunca se registra la URL pública ni el cuerpo completo.
 */
final class FactusResponseInterpreter {

    /** {@code validated_at}: hora de Colombia, reloj de 12 horas y sin zona (spike 0001, sección 2). */
    static final DateTimeFormatter VALIDATED_AT = DateTimeFormatter.ofPattern("dd-MM-yyyy hh:mm:ss a", Locale.ENGLISH);
    static final ZoneId FACTUS_ZONE = ZoneId.of("America/Bogota");
    static final int MAX_REASON_LENGTH = 1000;
    /**
     * Campos del 422 que dependen de la configuración del adaptador y no de la factura: un error en
     * ellos no es un rechazo (la factura quedaría REJECTED sin culpa), sino una falla técnica. El valor
     * es la configuración que hay que revisar, para decirlo en el log.
     */
    static final Map<String, String> CONFIGURATION_FIELDS = Map.of(
            "numbering_range_id", "invoicing.factus.numbering-range-id (variable FACTUS_NUMBERING_RANGE_ID)",
            "send_email", "invoicing.factus.send-email (variable FACTUS_SEND_EMAIL)");

    private static final Logger LOG = LoggerFactory.getLogger(FactusResponseInterpreter.class);
    private static final byte[] PDF_SIGNATURE = "%PDF-".getBytes(StandardCharsets.US_ASCII);

    IssuanceResult issuance(Invoice invoice, FactusResponse response) {
        return switch (response.status()) {
            case 200, 201 -> validated(invoice, read(response, BillResponse.class, "la validación").data());
            case 422 -> rejected(invoice, response);
            default -> throw technical("validación de la factura " + invoice.id(), response);
        };
    }

    InvoicePdf pdf(String number, FactusResponse response) {
        if (response.status() != 200) {
            throw technical("descarga del PDF de " + number, response);
        }
        PdfData data = read(response, PdfResponse.class, "la descarga del PDF").data();
        if (data == null || data.pdfBase64() == null || data.pdfBase64().isBlank()) {
            throw new FactusTechnicalException("Factus respondió la descarga del PDF de " + number + " sin el archivo");
        }
        byte[] content;
        try {
            content = Base64.getMimeDecoder().decode(data.pdfBase64());
        } catch (IllegalArgumentException e) {
            throw new FactusTechnicalException("El PDF de " + number + " no es Base64 válido");
        }
        if (content.length < PDF_SIGNATURE.length
                || !Arrays.equals(content, 0, PDF_SIGNATURE.length, PDF_SIGNATURE, 0, PDF_SIGNATURE.length)) {
            throw new FactusTechnicalException("Lo que Factus entregó como PDF de " + number + " no es un PDF");
        }
        String fileName = data.fileName() == null || data.fileName().isBlank() ? number : data.fileName();
        return new InvoicePdf(fileName, content);
    }

    // ----- 200/201 -----

    private IssuanceResult validated(Invoice invoice, BillData data) {
        if (data == null) {
            throw new FactusTechnicalException("Factus respondió la validación de la factura " + invoice.id()
                    + " sin data");
        }
        if (!Boolean.TRUE.equals(data.isValidated())) {
            throw new FactusTechnicalException("Factus registró la factura " + invoice.id() + " (" + data.number()
                    + ") pero la DIAN aún no la valida (is_validated=" + data.isValidated() + ")");
        }
        verifyIdentity(invoice, data);
        logNotifications(invoice, data);
        ElectronicDocument document = document(invoice, data);
        if (!document.officialTotal().equals(invoice.total())) {
            LOG.warn("El total oficial de la factura {} ({}) difiere del calculado: oficial {}, calculado {}. Se "
                    + "guarda el oficial; revisar el redondeo.", invoice.id(), document.number(),
                    document.officialTotal().amount().toPlainString(), invoice.total().amount().toPlainString());
        }
        return new IssuanceResult.Issued(document);
    }

    /**
     * La idempotencia de Factus es ciega (spike 0001, sorpresa 1): con un {@code reference_code} ya
     * usado devuelve el documento existente aunque el contenido sea otro. Si el código o la
     * identificación del cliente no coinciden, el documento es OTRA factura: no se marca emitida.
     * El total no entra aquí: si difiere con la misma identidad, es nuestra factura con otro redondeo.
     */
    private static void verifyIdentity(Invoice invoice, BillData data) {
        List<String> mismatches = new ArrayList<>();
        String expectedReference = invoice.id().value().toString();
        if (!expectedReference.equals(data.referenceCode())) {
            mismatches.add("reference_code esperado " + expectedReference + ", recibido " + data.referenceCode());
        }
        IdentificationDocument identification = invoice.customer().identification();
        String expectedCustomer = FactusCodes.documentType(identification.type()) + " " + identification.number();
        String receivedCustomer = receivedIdentification(data.customer());
        if (!expectedCustomer.equals(receivedCustomer)) {
            mismatches.add("identificación del cliente esperada " + expectedCustomer + ", recibida " + receivedCustomer);
        }
        if (!mismatches.isEmpty()) {
            LOG.error("La respuesta de Factus para la factura {} no corresponde a ella ({}): documento {}. No se "
                    + "marca emitida; conciliar a mano.", invoice.id(), String.join("; ", mismatches), data.number());
            throw new FactusTechnicalException("La respuesta de Factus no corresponde a la factura " + invoice.id()
                    + ": " + String.join("; ", mismatches));
        }
    }

    private static String receivedIdentification(ResponseCustomer customer) {
        if (customer == null) {
            return null;
        }
        String code = customer.identificationDocument() == null ? null : customer.identificationDocument().code();
        return code + " " + (customer.identification() == null ? null : customer.identification().strip());
    }

    private static void logNotifications(Invoice invoice, BillData data) {
        JsonNode errors = data.errors();
        if (errors != null && errors.isObject() && !errors.isEmpty()) {
            LOG.info("La DIAN validó la factura {} ({}) con notificaciones: {}", invoice.id(), data.number(),
                    String.join(", ", errors.propertyNames()));
        }
    }

    private static ElectronicDocument document(Invoice invoice, BillData data) {
        try {
            Objects.requireNonNull(data.links(), "links");
            Objects.requireNonNull(data.totals(), "totals");
            return new ElectronicDocument(
                    data.number(),
                    data.cufe(),
                    URI.create(Objects.requireNonNull(data.links().qr(), "links.qr")),
                    URI.create(Objects.requireNonNull(data.links().publicUrl(), "links.public_url")),
                    validatedAt(data.validatedAt()),
                    Money.of(Objects.requireNonNull(data.totals().total(), "totals.total")));
        } catch (DomainException | IllegalArgumentException | NullPointerException | DateTimeParseException e) {
            // La DIAN sí la validó: sigue PENDING y un reintento recupera el mismo documento (idempotencia).
            LOG.error("La DIAN validó la factura {} ({}) pero el documento que devolvió Factus es ilegible: {}",
                    invoice.id(), data.number(), e.getClass().getSimpleName());
            throw new FactusTechnicalException("Documento validado ilegible para la factura " + invoice.id(), e);
        }
    }

    private static Instant validatedAt(String value) {
        return LocalDateTime.parse(Objects.requireNonNull(value, "validated_at"), VALIDATED_AT)
                .atZone(FACTUS_ZONE).toInstant();
    }

    // ----- 422 -----

    private IssuanceResult rejected(Invoice invoice, FactusResponse response) {
        ValidationErrorResponse body = read(response, ValidationErrorResponse.class, "el rechazo");
        Map<String, List<String>> errors = fieldErrors(body);
        List<String> settings = errors.keySet().stream()
                .map(field -> CONFIGURATION_FIELDS.get(field.split("\\.")[0]))
                .filter(Objects::nonNull).distinct().sorted().toList();
        if (!settings.isEmpty()) {
            LOG.error("Factus rechazó la configuración del adaptador al validar la factura {}. Revise {}. Respuesta "
                    + "de Factus: {}. La factura sigue PENDING: corrija la configuración, reinicie el servicio y "
                    + "reintente la emisión.", invoice.id(), String.join(" y ", settings), errors);
            throw new FactusTechnicalException("Factus rechazó la configuración del adaptador (HTTP 422); revisar "
                    + String.join(" y ", settings));
        }
        StringBuilder reason = new StringBuilder("Factus no validó la factura: ");
        if (errors.isEmpty()) {
            reason.append(body.message() == null ? "error de validación sin detalle" : body.message());
        } else {
            reason.append(String.join("; ", errors.entrySet().stream()
                    .map(entry -> entry.getKey() + ": " + String.join(" ", entry.getValue())).toList()));
        }
        String text = reason.length() > MAX_REASON_LENGTH ? reason.substring(0, MAX_REASON_LENGTH - 1) + "…"
                : reason.toString();
        LOG.info("Factus rechazó la factura {} (422): {}", invoice.id(), errors.keySet());
        return new IssuanceResult.Rejected(text);
    }

    /** {@code data.errors} de Laravel: ruta del campo → lista de mensajes (tolera un mensaje suelto). */
    private static Map<String, List<String>> fieldErrors(ValidationErrorResponse body) {
        Map<String, List<String>> errors = new LinkedHashMap<>();
        JsonNode node = body.data() == null ? null : body.data().errors();
        if (node == null || !node.isObject()) {
            return errors;
        }
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            JsonNode messages = field.getValue();
            List<String> texts = new ArrayList<>();
            if (messages.isArray()) {
                messages.forEach(message -> texts.add(message.asString()));
            } else {
                texts.add(messages.asString());
            }
            errors.put(field.getKey(), texts);
        }
        return errors;
    }

    // ----- Comunes -----

    private static <T> T read(FactusResponse response, Class<T> type, String what) {
        try {
            T body = FactusJson.MAPPER.readValue(response.body(), type);
            if (body == null) {
                throw new FactusTechnicalException("Factus respondió " + what + " con un cuerpo vacío");
            }
            return body;
        } catch (JacksonException e) {
            throw new FactusTechnicalException("Factus respondió " + what + " (HTTP " + response.status()
                    + ") con un cuerpo ilegible");
        }
    }

    private static FactusTechnicalException technical(String operation, FactusResponse response) {
        String meaning = switch (response.status()) {
            case 401 -> "token rechazado incluso después de renovarlo";
            case 409 -> "Factus tiene la factura pendiente por enviar a la DIAN";
            case 429 -> "límite de peticiones de Factus alcanzado (Retry-After: "
                    + (response.retryAfter() == null ? "no informado" : response.retryAfter()) + ")";
            default -> response.status() >= 500 ? "error del servidor de Factus" : "respuesta HTTP no esperada";
        };
        return new FactusTechnicalException(operation + ": HTTP " + response.status() + ", " + meaning);
    }
}

package com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus;

import com.facturaflow.invoicing.application.exception.ElectronicInvoiceProviderUnavailableException.Reason;
import com.facturaflow.invoicing.application.port.out.InvoicePdf;
import com.facturaflow.invoicing.application.port.out.IssuanceResult;
import com.facturaflow.invoicing.domain.exception.DomainException;
import com.facturaflow.invoicing.domain.model.CreditNote;
import com.facturaflow.invoicing.domain.model.ElectronicDocument;
import com.facturaflow.invoicing.domain.model.IdentificationDocument;
import com.facturaflow.invoicing.domain.model.Invoice;
import com.facturaflow.invoicing.domain.model.Money;
import com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusDtos.BillData;
import com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusDtos.BillResponse;
import com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusDtos.CreditNoteData;
import com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusDtos.CreditNoteResponse;
import com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusDtos.Links;
import com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusDtos.PdfData;
import com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusDtos.PdfResponse;
import com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusDtos.ResponseCustomer;
import com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusDtos.Totals;
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
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Decide qué significa cada respuesta de Factus (tabla del ADR 0008), con la MISMA tabla para
 * facturas y notas crédito. Respuestas de negocio:
 * <ul>
 *   <li>200/201 con {@code is_validated: true} y que corresponde a nuestro documento → {@code Issued}.
 *       {@code data.errors} trae entonces <i>notificaciones</i> de la DIAN (un mapa en facturas, una
 *       lista de textos en notas, p. ej. {@code CBF02}), no errores: se registran.</li>
 *   <li>422 por los datos del documento → {@code Rejected}, con los mensajes de Factus como motivo.</li>
 * </ul>
 * Todo lo demás es una {@link FactusTechnicalException} y el documento sigue PENDING: {@code
 * is_validated: false}, 409 (pendiente en la DIAN), 401 tras renovar el token, 429, 5xx, otros códigos,
 * cuerpos ilegibles, un 422 por la configuración del adaptador y una respuesta que no corresponde al
 * documento. Nunca se registra la URL pública ni el cuerpo completo.
 *
 * <p>Las diferencias entre los dos documentos (forma de la respuesta, qué identidad verificar, qué
 * campos del 422 son configuración) viven en {@link Expected} y en los lectores de cada respuesta;
 * la interpretación es una sola.
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
    /**
     * Lo mismo para las notas crédito. {@code bill_number} ("La factura referenciada no existe", spike
     * 0002) tampoco es culpa de la nota: la factura que nosotros tenemos ISSUED no existe en esta cuenta
     * de Factus (p. ej. se emitió con el proveedor falso o en otro entorno).
     */
    static final Map<String, String> CREDIT_NOTE_CONFIGURATION_FIELDS = Map.of(
            "numbering_range_id", "invoicing.factus.credit-note-numbering-range-id (variable "
                    + "FACTUS_CREDIT_NOTE_NUMBERING_RANGE_ID)",
            "send_email", "invoicing.factus.send-email (variable FACTUS_SEND_EMAIL)",
            "bill_number", "que la factura acreditada se haya emitido en esta misma cuenta de Factus (no con el "
                    + "proveedor falso ni en otro entorno)");

    private static final Logger LOG = LoggerFactory.getLogger(FactusResponseInterpreter.class);
    private static final byte[] PDF_SIGNATURE = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    /** Código de regla al inicio de una notificación en texto: {@code "Regla: CBF02, Notificación: ..."}. */
    private static final Pattern RULE = Pattern.compile("Regla:\\s*([A-Za-z0-9]+)");

    /**
     * Lo que debe cumplir la respuesta para ser NUESTRO documento.
     *
     * @param subject        "la factura &lt;id&gt;" o "la nota crédito &lt;id&gt;", para los mensajes
     * @param noun           "la factura" o "la nota crédito", para el motivo de un rechazo
     * @param billNumber     número de la factura acreditada que debe venir en {@code bill.number}; {@code null} en facturas
     */
    private record Expected(String subject, String noun, String referenceCode, IdentificationDocument customer,
                            String billNumber, Money total, Map<String, String> configurationFields) {
    }

    /** Vista común de un documento validado, sea factura o nota crédito. */
    private record Validated(String referenceCode, String number, Boolean isValidated, String validatedAt,
                             JsonNode errors, ResponseCustomer customer, Totals totals, String uniqueCode,
                             Links links, String billNumber) {
    }

    IssuanceResult issuance(Invoice invoice, FactusResponse response) {
        Expected expected = new Expected("la factura " + invoice.id(), "la factura", invoice.id().value().toString(),
                invoice.customer().identification(), null, invoice.total(), CONFIGURATION_FIELDS);
        return issuance(expected, response, body -> {
            BillData data = read(body, BillResponse.class, "la validación").data();
            return data == null ? null : new Validated(data.referenceCode(), data.number(), data.isValidated(),
                    data.validatedAt(), data.errors(), data.customer(), data.totals(), data.cufe(), data.links(), null);
        });
    }

    /** La nota debe venir con nuestro {@code reference_code}, el cliente de la factura y {@code bill.number}. */
    IssuanceResult creditNoteIssuance(CreditNote creditNote, Invoice creditedInvoice, FactusResponse response) {
        String billNumber = creditedInvoice.electronicDocument().map(ElectronicDocument::number).orElseThrow();
        Expected expected = new Expected("la nota crédito " + creditNote.id(), "la nota crédito",
                creditNote.id().value().toString(), creditedInvoice.customer().identification(), billNumber,
                creditNote.total(), CREDIT_NOTE_CONFIGURATION_FIELDS);
        return issuance(expected, response, body -> {
            CreditNoteData data = read(body, CreditNoteResponse.class, "la validación").data();
            return data == null ? null : new Validated(data.referenceCode(), data.number(), data.isValidated(),
                    data.validatedAt(), data.errors(), data.customer(), data.totals(), data.cude(), data.links(),
                    data.bill() == null ? null : data.bill().number());
        });
    }

    /** El PDF tiene la misma forma para facturas y notas crédito (spike 0002, sección 8). */
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

    private IssuanceResult issuance(Expected expected, FactusResponse response,
                                    Function<FactusResponse, Validated> reader) {
        return switch (response.status()) {
            case 200, 201 -> validated(expected, reader.apply(response));
            case 422 -> rejected(expected, response);
            default -> throw technical("validación de " + expected.subject(), response);
        };
    }

    // ----- 200/201 -----

    private IssuanceResult validated(Expected expected, Validated data) {
        if (data == null) {
            throw new FactusTechnicalException("Factus respondió la validación de " + expected.subject() + " sin data");
        }
        if (!Boolean.TRUE.equals(data.isValidated())) {
            throw new FactusTechnicalException("Factus registró " + expected.subject() + " (" + data.number()
                    + ") pero la DIAN aún no la valida (is_validated=" + data.isValidated() + ")");
        }
        verifyIdentity(expected, data);
        logNotifications(expected, data);
        ElectronicDocument document = document(expected, data);
        if (!document.officialTotal().equals(expected.total())) {
            LOG.warn("El total oficial de {} ({}) difiere del calculado: oficial {}, calculado {}. Se guarda el "
                    + "oficial; revisar el redondeo.", expected.subject(), document.number(),
                    document.officialTotal().amount().toPlainString(), expected.total().amount().toPlainString());
        }
        return new IssuanceResult.Issued(document);
    }

    /**
     * La idempotencia de Factus es ciega (spikes 0001 y 0002): con un {@code reference_code} ya usado
     * devuelve el documento existente aunque el contenido sea otro. Si el código, la identificación
     * del cliente o (en una nota) la factura acreditada no coinciden, el documento es OTRO: no se marca
     * emitido. El total no entra aquí: si difiere con la misma identidad, es el nuestro con otro redondeo.
     */
    private static void verifyIdentity(Expected expected, Validated data) {
        List<String> mismatches = new ArrayList<>();
        if (!expected.referenceCode().equals(data.referenceCode())) {
            mismatches.add("reference_code esperado " + expected.referenceCode() + ", recibido " + data.referenceCode());
        }
        IdentificationDocument identification = expected.customer();
        String expectedCustomer = FactusCodes.documentType(identification.type()) + " " + identification.number();
        String receivedCustomer = receivedIdentification(data.customer());
        if (!expectedCustomer.equals(receivedCustomer)) {
            mismatches.add("identificación del cliente esperada " + expectedCustomer + ", recibida " + receivedCustomer);
        }
        if (expected.billNumber() != null && !expected.billNumber().equals(data.billNumber())) {
            mismatches.add("factura acreditada (bill.number) esperada " + expected.billNumber() + ", recibida "
                    + data.billNumber());
        }
        if (!mismatches.isEmpty()) {
            LOG.error("La respuesta de Factus para {} no corresponde a ella ({}): documento {}. No se marca "
                    + "emitida; conciliar a mano.", expected.subject(), String.join("; ", mismatches), data.number());
            throw new FactusTechnicalException("La respuesta de Factus no corresponde a " + expected.subject() + ": "
                    + String.join("; ", mismatches));
        }
    }

    private static String receivedIdentification(ResponseCustomer customer) {
        if (customer == null) {
            return null;
        }
        String code = customer.identificationDocument() == null ? null : customer.identificationDocument().code();
        return code + " " + (customer.identification() == null ? null : customer.identification().strip());
    }

    /** Solo los códigos de regla: un mapa (facturas) o una lista de textos "Regla: X, ..." (notas). */
    private static void logNotifications(Expected expected, Validated data) {
        List<String> codes = notificationCodes(data.errors());
        if (!codes.isEmpty()) {
            LOG.info("La DIAN validó {} ({}) con notificaciones: {}", expected.subject(), data.number(),
                    String.join(", ", codes));
        }
    }

    static List<String> notificationCodes(JsonNode errors) {
        List<String> codes = new ArrayList<>();
        if (errors == null) {
            return codes;
        }
        if (errors.isObject()) {
            codes.addAll(errors.propertyNames());
        } else if (errors.isArray()) {
            for (JsonNode notification : errors) {
                Matcher rule = RULE.matcher(notification.asString());
                codes.add(rule.find() ? rule.group(1) : "sin código");
            }
        }
        return codes;
    }

    private static ElectronicDocument document(Expected expected, Validated data) {
        try {
            Objects.requireNonNull(data.links(), "links");
            Objects.requireNonNull(data.totals(), "totals");
            return new ElectronicDocument(
                    data.number(),
                    data.uniqueCode(),
                    URI.create(Objects.requireNonNull(data.links().qr(), "links.qr")),
                    URI.create(Objects.requireNonNull(data.links().publicUrl(), "links.public_url")),
                    validatedAt(data.validatedAt()),
                    Money.of(Objects.requireNonNull(data.totals().total(), "totals.total")));
        } catch (DomainException | IllegalArgumentException | NullPointerException | DateTimeParseException e) {
            // La DIAN sí lo validó: sigue PENDING y un reintento recupera el mismo documento (idempotencia).
            LOG.error("La DIAN validó {} ({}) pero el documento que devolvió Factus es ilegible: {}",
                    expected.subject(), data.number(), e.getClass().getSimpleName());
            throw new FactusTechnicalException("Documento validado ilegible para " + expected.subject(), e);
        }
    }

    private static Instant validatedAt(String value) {
        return LocalDateTime.parse(Objects.requireNonNull(value, "validated_at"), VALIDATED_AT)
                .atZone(FACTUS_ZONE).toInstant();
    }

    // ----- 422 -----

    private IssuanceResult rejected(Expected expected, FactusResponse response) {
        ValidationErrorResponse body = read(response, ValidationErrorResponse.class, "el rechazo");
        Map<String, List<String>> errors = fieldErrors(body);
        List<String> settings = errors.keySet().stream()
                .map(field -> expected.configurationFields().get(field.split("\\.")[0]))
                .filter(Objects::nonNull).distinct().sorted().toList();
        if (!settings.isEmpty()) {
            LOG.error("Factus rechazó la configuración del adaptador al validar {}. Revise {}. Respuesta de Factus: "
                    + "{}. El documento sigue PENDING: corrija la configuración, reinicie el servicio y reintente la "
                    + "emisión.", expected.subject(), String.join(" y ", settings), errors);
            throw new FactusTechnicalException("Factus rechazó la configuración del adaptador (HTTP 422); revisar "
                    + String.join(" y ", settings));
        }
        StringBuilder reason = new StringBuilder("Factus no validó " + expected.noun() + ": ");
        if (errors.isEmpty()) {
            reason.append(body.message() == null ? "error de validación sin detalle" : body.message());
        } else {
            reason.append(String.join("; ", errors.entrySet().stream()
                    .map(entry -> entry.getKey() + ": " + String.join(" ", entry.getValue())).toList()));
        }
        String text = reason.length() > MAX_REASON_LENGTH ? reason.substring(0, MAX_REASON_LENGTH - 1) + "…"
                : reason.toString();
        LOG.info("Factus rechazó {} (422): {}", expected.subject(), errors.keySet());
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
            case 409 -> "Factus tiene el documento pendiente por enviar a la DIAN";
            case 429 -> "límite de peticiones de Factus alcanzado (Retry-After: "
                    + (response.retryAfter() == null ? "no informado" : response.retryAfter()) + ")";
            default -> response.status() >= 500 ? "error del servidor de Factus" : "respuesta HTTP no esperada";
        };
        // Un 429 que no se reintentó (Retry-After demasiado largo) le pasa ese Retry-After al cliente (ADR 0010).
        return response.status() == 429
                ? new FactusTechnicalException(operation + ": HTTP 429, " + meaning, Reason.RATE_LIMITED,
                        FactusResilience.retryAfter(response), null)
                : new FactusTechnicalException(operation + ": HTTP " + response.status() + ", " + meaning);
    }
}

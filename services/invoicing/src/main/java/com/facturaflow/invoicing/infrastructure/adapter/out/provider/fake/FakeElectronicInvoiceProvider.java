package com.facturaflow.invoicing.infrastructure.adapter.out.provider.fake;

import com.facturaflow.invoicing.application.exception.ElectronicInvoiceProviderUnavailableException;
import com.facturaflow.invoicing.application.port.out.ElectronicCreditNoteProvider;
import com.facturaflow.invoicing.application.port.out.ElectronicInvoiceProvider;
import com.facturaflow.invoicing.application.port.out.InvoicePdf;
import com.facturaflow.invoicing.application.port.out.IssuanceResult;
import com.facturaflow.invoicing.domain.model.CreditNote;
import com.facturaflow.invoicing.domain.model.ElectronicDocument;
import com.facturaflow.invoicing.domain.model.Invoice;
import com.facturaflow.invoicing.domain.model.Money;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Proveedor de facturación FALSO para desarrollo y demos (ADR 0006). Activo con
 * {@code invoicing.provider=fake}. Implementa los dos puertos: facturas y notas crédito (ADR 0009).
 * Es determinista y se controla con la observación del documento (factura o nota):
 * <ul>
 *   <li>contiene {@value #SIMULATE_OUTAGE}: lanza la excepción de falla técnica (tiene prioridad);</li>
 *   <li>contiene {@value #SIMULATE_REJECTION}: la rechaza con un motivo de ejemplo;</li>
 *   <li>en otro caso: la emite con datos claramente falsos.</li>
 * </ul>
 * Los datos de una emisión falsa no tienen validez: el número empieza con {@code FAKE-} (y
 * {@code FAKE-NC-} en las notas), el código único (CUFE/CUDE) con {@value #FAKE_CUFE_MARK} (el
 * dominio solo admite hexadecimal, así que la marca son ceros) y los enlaces usan el dominio
 * reservado {@code .invalid} (RFC 2606), que nunca resuelve.
 *
 * <p>El PDF es un documento mínimo válido, armado a mano, que dice "DOCUMENTO FALSO - SIN VALIDEZ".
 * {@value #SIMULATE_OUTAGE} también hace fallar su descarga.
 */
@Component
@ConditionalOnProperty(name = "invoicing.provider", havingValue = "fake")
class FakeElectronicInvoiceProvider implements ElectronicInvoiceProvider, ElectronicCreditNoteProvider {

    static final String SIMULATE_REJECTION = "SIMULAR_RECHAZO";
    static final String SIMULATE_OUTAGE = "SIMULAR_CAIDA";
    static final String FAKE_CUFE_MARK = "0000000000000000";
    static final String NUMBER_PREFIX = "FAKE-";
    static final String CREDIT_NOTE_NUMBER_PREFIX = "FAKE-NC-";
    static final String QR_BASE_URL = "https://fake-provider.invalid/qr/";
    static final String PUBLIC_BASE_URL = "https://fake-provider.invalid/invoices/";

    private static final Logger LOG = LoggerFactory.getLogger(FakeElectronicInvoiceProvider.class);

    private final Clock clock;

    FakeElectronicInvoiceProvider(Clock clock) {
        this.clock = clock;
        LOG.warn("Proveedor de facturación electrónica FALSO activo (invoicing.provider=fake): "
                + "las facturas 'emitidas' no tienen validez ante la DIAN.");
    }

    @Override
    public IssuanceResult issue(Invoice invoice) {
        if (simulates(invoice.observation().orElse(""), SIMULATE_OUTAGE)) {
            throw outage(invoice.id().value(), false);
        }
        return issue(invoice.observation().orElse(""), NUMBER_PREFIX + invoice.referenceCode().value(),
                invoice.id().value(), invoice.total());
    }

    @Override
    public InvoicePdf downloadPdf(Invoice issuedInvoice) {
        if (simulates(issuedInvoice.observation().orElse(""), SIMULATE_OUTAGE)) {
            throw outage(issuedInvoice.id().value(), false);
        }
        String number = issuedInvoice.electronicDocument().map(ElectronicDocument::number)
                .orElseThrow(() -> new IllegalStateException("La factura " + issuedInvoice.id() + " no está emitida"));
        return pdf(number, "Factura " + number);
    }

    @Override
    public IssuanceResult issue(CreditNote creditNote, Invoice creditedInvoice) {
        if (simulates(creditNote.observation().orElse(""), SIMULATE_OUTAGE)) {
            throw outage(creditNote.id().value(), true);
        }
        return issue(creditNote.observation().orElse(""), CREDIT_NOTE_NUMBER_PREFIX + creditNote.referenceCode().value(),
                creditNote.id().value(), creditNote.total());
    }

    @Override
    public InvoicePdf downloadPdf(CreditNote issuedCreditNote) {
        if (simulates(issuedCreditNote.observation().orElse(""), SIMULATE_OUTAGE)) {
            throw outage(issuedCreditNote.id().value(), true);
        }
        String number = issuedCreditNote.electronicDocument().map(ElectronicDocument::number)
                .orElseThrow(() -> new IllegalStateException("La nota crédito " + issuedCreditNote.id()
                        + " no está emitida"));
        return pdf(number, "Nota credito " + number);
    }

    private IssuanceResult issue(String observation, String number, UUID documentId,
                                 Money total) {
        if (simulates(observation, SIMULATE_REJECTION)) {
            return new IssuanceResult.Rejected("Rechazo simulado por el proveedor falso (" + SIMULATE_REJECTION
                    + "): la DIAN no validó el documento.");
        }
        return new IssuanceResult.Issued(new ElectronicDocument(
                number,
                fakeUniqueCode(documentId),
                URI.create(QR_BASE_URL + number),
                URI.create(PUBLIC_BASE_URL + number),
                clock.instant(),
                total));
    }

    private static InvoicePdf pdf(String number, String title) {
        return new InvoicePdf(number, FakePdf.render("DOCUMENTO FALSO - SIN VALIDEZ", title,
                "Generado por el proveedor falso de FacturaFlow (invoicing.provider=fake)."));
    }

    private static boolean simulates(String observation, String word) {
        return observation.contains(word);
    }

    private static ElectronicInvoiceProviderUnavailableException outage(UUID documentId, boolean creditNote) {
        String detail = "caída simulada por el proveedor falso (" + SIMULATE_OUTAGE + ")";
        return creditNote
                ? ElectronicInvoiceProviderUnavailableException.forCreditNote(documentId, detail, null)
                : new ElectronicInvoiceProviderUnavailableException(documentId, detail, null);
    }

    /** 96 caracteres hexadecimales, como un CUFE o CUDE real: la marca de ceros y 80 caracteres derivados del id. */
    private static String fakeUniqueCode(UUID documentId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-384")
                    .digest(documentId.toString().getBytes(StandardCharsets.UTF_8));
            return FAKE_CUFE_MARK + HexFormat.of().formatHex(digest).substring(0, 96 - FAKE_CUFE_MARK.length());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("La JVM no ofrece SHA-384", e);
        }
    }
}

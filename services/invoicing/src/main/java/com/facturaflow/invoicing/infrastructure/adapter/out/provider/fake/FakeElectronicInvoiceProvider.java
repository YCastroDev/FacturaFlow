package com.facturaflow.invoicing.infrastructure.adapter.out.provider.fake;

import com.facturaflow.invoicing.application.exception.ElectronicInvoiceProviderUnavailableException;
import com.facturaflow.invoicing.application.port.out.ElectronicInvoiceProvider;
import com.facturaflow.invoicing.application.port.out.IssuanceResult;
import com.facturaflow.invoicing.domain.model.ElectronicDocument;
import com.facturaflow.invoicing.domain.model.Invoice;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Proveedor de facturación FALSO para desarrollo y demos (ADR 0006). Activo con
 * {@code invoicing.provider=fake}. Es determinista y se controla con la observación de la factura:
 * <ul>
 *   <li>contiene {@value #SIMULATE_OUTAGE}: lanza la excepción de falla técnica (tiene prioridad);</li>
 *   <li>contiene {@value #SIMULATE_REJECTION}: la rechaza con un motivo de ejemplo;</li>
 *   <li>en otro caso: la emite con datos claramente falsos.</li>
 * </ul>
 * Los datos de una emisión falsa no tienen validez: el número empieza con {@code FAKE-}, el CUFE
 * con {@value #FAKE_CUFE_MARK} (el dominio solo admite hexadecimal, así que la marca son ceros) y
 * la URL del PDF usa el dominio reservado {@code .invalid} (RFC 2606), que nunca resuelve.
 */
@Component
@ConditionalOnProperty(name = "invoicing.provider", havingValue = "fake")
class FakeElectronicInvoiceProvider implements ElectronicInvoiceProvider {

    static final String SIMULATE_REJECTION = "SIMULAR_RECHAZO";
    static final String SIMULATE_OUTAGE = "SIMULAR_CAIDA";
    static final String FAKE_CUFE_MARK = "0000000000000000";
    static final String NUMBER_PREFIX = "FAKE-";
    static final String PDF_BASE_URL = "https://fake-provider.invalid/invoices/";

    private static final Logger LOG = LoggerFactory.getLogger(FakeElectronicInvoiceProvider.class);

    private final Clock clock;

    FakeElectronicInvoiceProvider(Clock clock) {
        this.clock = clock;
        LOG.warn("Proveedor de facturación electrónica FALSO activo (invoicing.provider=fake): "
                + "las facturas 'emitidas' no tienen validez ante la DIAN.");
    }

    @Override
    public IssuanceResult issue(Invoice invoice) {
        String observation = invoice.observation().orElse("");
        if (observation.contains(SIMULATE_OUTAGE)) {
            throw new ElectronicInvoiceProviderUnavailableException(invoice.id().value(),
                    "caída simulada por el proveedor falso (" + SIMULATE_OUTAGE + ")", null);
        }
        if (observation.contains(SIMULATE_REJECTION)) {
            return new IssuanceResult.Rejected("Rechazo simulado por el proveedor falso (" + SIMULATE_REJECTION
                    + "): la DIAN no validó el documento.");
        }
        String number = NUMBER_PREFIX + invoice.referenceCode().value();
        return new IssuanceResult.Issued(new ElectronicDocument(
                number,
                fakeCufe(invoice),
                URI.create(PDF_BASE_URL + number + ".pdf"),
                clock.instant(),
                invoice.total()));
    }

    /** 96 caracteres hexadecimales, como un CUFE real: la marca de ceros y 80 caracteres derivados del id. */
    private static String fakeCufe(Invoice invoice) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-384")
                    .digest(invoice.id().value().toString().getBytes(StandardCharsets.UTF_8));
            return FAKE_CUFE_MARK + HexFormat.of().formatHex(digest).substring(0, 96 - FAKE_CUFE_MARK.length());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("La JVM no ofrece SHA-384", e);
        }
    }
}

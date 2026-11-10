package com.facturaflow.sales;

import static com.facturaflow.sales.domain.model.SaleFixtures.CREATED_AT;
import static com.facturaflow.sales.domain.model.SaleFixtures.SALE_DATE;
import static com.facturaflow.sales.domain.model.SaleFixtures.SOLD_BY;
import static com.facturaflow.sales.domain.model.SaleFixtures.customer;
import static com.facturaflow.sales.domain.model.SaleFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;

import com.facturaflow.sales.application.port.out.RequestHash;
import com.facturaflow.sales.application.port.out.SaleRepository;
import com.facturaflow.sales.domain.model.PaymentMethod;
import com.facturaflow.sales.domain.model.PaymentTerms;
import com.facturaflow.sales.domain.model.ReferenceCode;
import com.facturaflow.sales.domain.model.Sale;
import com.facturaflow.sales.domain.model.SaleId;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Contrato entre el dashboard de negocio ({@code infra/grafana/dashboards/facturaflow-negocio.json}, el MISMO archivo
 * que carga Grafana) y las métricas que registra sales (ADR 0020, D7). Cada métrica {@code sales_*} que consulta el
 * dashboard, y cada etiqueta con valor fijo que filtra ({@code status="invoiced"}), debe existir en el
 * {@code MeterRegistry}: renombrar una métrica rompe este test en vez de dejar un panel vacío.
 *
 * <p>Nombres de Prometheus (vía OTLP) → Micrometer: puntos por guiones bajos, más sufijos de unidad
 * ({@code _milliseconds}, {@code _seconds}, {@code _events}) y de tipo ({@code _total}, {@code _sum}, {@code _count},
 * {@code _bucket}, {@code _max}), que aquí se quitan. Copia deliberada en invoicing (con su prefijo).
 */
@SpringBootTest(properties = "sales.outbox.relay.enabled=false")
@Import(TestcontainersConfiguration.class)
class BusinessMetricsContractTest {

    static final Path DASHBOARD = Path.of("../../infra/grafana/dashboards/facturaflow-negocio.json");
    private static final String PREFIX = "sales_";
    private static final Pattern METRIC = Pattern.compile("\\b(" + PREFIX + "[a-z_]+)(\\{([^}]*)})?");
    private static final Pattern FIXED_LABEL = Pattern.compile("([a-z_]+)\\s*=\\s*\"([^\"$]+)\"");
    private static final Pattern SUFFIXES =
            Pattern.compile("(_max)?(_milliseconds|_seconds|_events|_bytes)?(_total|_sum|_count|_bucket)?$");

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private SaleRepository sales;

    @Autowired
    private JdbcClient jdbc;

    /** Métricas del dashboard (nombre de Micrometer → etiquetas fijas que filtra). */
    static Map<String, Set<Map.Entry<String, String>>> dashboardMetrics() {
        String json;
        try {
            json = Files.readString(DASHBOARD);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer el dashboard " + DASHBOARD.toAbsolutePath(), e);
        }
        Map<String, Set<Map.Entry<String, String>>> metrics = new LinkedHashMap<>();
        Matcher matcher = METRIC.matcher(json.replace("\\\"", "\""));
        while (matcher.find()) {
            String name = SUFFIXES.matcher(matcher.group(1)).replaceFirst("").replace('_', '.');
            Set<Map.Entry<String, String>> labels = metrics.computeIfAbsent(name, key -> new LinkedHashSet<>());
            if (matcher.group(3) != null) {
                Matcher label = FIXED_LABEL.matcher(matcher.group(3));
                while (label.find()) {
                    labels.add(Map.entry(label.group(1), label.group(2)));
                }
            }
        }
        return metrics;
    }

    /** Micrometer separa con puntos, pero algunos nombres llevan guion bajo dentro de un segmento. */
    private boolean registered(String dottedName, Map.Entry<String, String> label) {
        return meters.getMeters().stream().map(Meter::getId)
                .filter(id -> id.getName().replace('_', '.').equals(dottedName))
                .anyMatch(id -> label == null || label.getValue().equals(id.getTag(label.getKey())));
    }

    @Test
    @DisplayName("cada métrica de sales que consulta el dashboard de negocio existe, con las etiquetas que filtra")
    void dashboardMetricsAreRegistered() {
        // Las métricas por medio de pago nacen con la primera venta.
        jdbc.sql("TRUNCATE sale_line_taxes, sale_lines, sales, outbox_events").update();
        sales.saveNew(Sale.confirm(SaleId.generate(), new ReferenceCode("VENTA-CONTRATO-1"), SALE_DATE, CREATED_AT,
                customer(), List.of(line("1", "1000", "0", "19")), PaymentMethod.CASH, PaymentTerms.cash(), null,
                SOLD_BY), new RequestHash("d".repeat(64)));

        Map<String, Set<Map.Entry<String, String>>> metrics = dashboardMetrics();

        assertThat(metrics).as("el dashboard consulta métricas de sales").isNotEmpty();
        metrics.forEach((name, labels) -> {
            assertThat(registered(name, null)).as("métrica %s registrada", name).isTrue();
            labels.forEach(label -> assertThat(registered(name, label))
                    .as("métrica %s con %s=%s", name, label.getKey(), label.getValue()).isTrue());
        });
    }
}

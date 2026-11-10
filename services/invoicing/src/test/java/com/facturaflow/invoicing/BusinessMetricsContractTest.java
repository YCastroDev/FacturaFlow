package com.facturaflow.invoicing;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Contrato entre el dashboard de negocio ({@code infra/grafana/dashboards/facturaflow-negocio.json}, el MISMO archivo
 * que carga Grafana) y las métricas que registra invoicing (ADR 0020, D7). Cada métrica {@code invoicing_*} que consulta el
 * dashboard, y cada etiqueta con valor fijo que filtra ({@code outcome="dead-lettered"}), debe existir en el
 * {@code MeterRegistry}: renombrar una métrica rompe este test en vez de dejar un panel vacío.
 *
 * <p>Nombres de Prometheus (vía OTLP) → Micrometer: puntos por guiones bajos, más sufijos de unidad
 * ({@code _milliseconds}, {@code _seconds}, {@code _events}) y de tipo ({@code _total}, {@code _sum}, {@code _count},
 * {@code _bucket}, {@code _max}), que aquí se quitan. Copia deliberada de la de sales (con su prefijo).
 */
@SpringBootTest(properties = "invoicing.outbox.relay.enabled=false")
@Import(TestcontainersConfiguration.class)
class BusinessMetricsContractTest {

    static final Path DASHBOARD = Path.of("../../infra/grafana/dashboards/facturaflow-negocio.json");
    private static final String PREFIX = "invoicing_";
    private static final Pattern METRIC = Pattern.compile("\\b(" + PREFIX + "[a-z_]+)(\\{([^}]*)})?");
    private static final Pattern FIXED_LABEL = Pattern.compile("([a-z_]+)\\s*=\\s*\"([^\"$]+)\"");
    private static final Pattern SUFFIXES =
            Pattern.compile("(_max)?(_milliseconds|_seconds|_events|_bytes)?(_total|_sum|_count|_bucket)?$");

    @Autowired
    private MeterRegistry meters;

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
    @DisplayName("cada métrica de invoicing que consulta el dashboard de negocio existe, con las etiquetas que filtra")
    void dashboardMetricsAreRegistered() {
        // Todas las métricas de invoicing que usa el dashboard se registran al arrancar (en cero).
        Map<String, Set<Map.Entry<String, String>>> metrics = dashboardMetrics();

        assertThat(metrics).as("el dashboard consulta métricas de invoicing").isNotEmpty();
        metrics.forEach((name, labels) -> {
            assertThat(registered(name, null)).as("métrica %s registrada", name).isTrue();
            labels.forEach(label -> assertThat(registered(name, label))
                    .as("métrica %s con %s=%s", name, label.getKey(), label.getValue()).isTrue());
        });
    }
}

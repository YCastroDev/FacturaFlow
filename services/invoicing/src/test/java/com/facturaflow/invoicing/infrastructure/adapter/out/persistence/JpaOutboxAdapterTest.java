package com.facturaflow.invoicing.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.facturaflow.invoicing.TestcontainersConfiguration;
import com.facturaflow.invoicing.application.port.out.OutboxBatch;
import com.facturaflow.invoicing.application.port.out.OutboxMessage;
import com.facturaflow.invoicing.application.port.out.OutboxStatus;
import com.facturaflow.invoicing.application.port.out.PublicationResult;
import com.facturaflow.invoicing.domain.model.InvoiceEvent;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * El outbox contra PostgreSQL real (ADR 0015; mismas garantías que el de sales, ADR 0013): bloqueo con {@code FOR UPDATE SKIP LOCKED}, marcado solo de
 * lo confirmado y la consulta de pendientes. El relay programado está apagado: aquí se invoca a mano.
 */
@SpringBootTest(properties = "invoicing.outbox.relay.enabled=false")
@Import(TestcontainersConfiguration.class)
class JpaOutboxAdapterTest {

    private static final Instant T0 = Instant.parse("2026-10-23T15:00:00Z");

    @Autowired
    private JpaOutboxAdapter outbox;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("TRUNCATE outbox_events").update();
    }

    /** Inserta un evento pendiente ocurrido {@code second} segundos después de T0. */
    private UUID pendingEvent(int second) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, event_version,
                                                   routing_key, payload, occurred_at)
                        VALUES (:id, 'Invoice', :aggregateId, 'InvoiceIssued', 1, 'invoice.issued.v1', :payload, :occurredAt)""")
                .param("id", id).param("aggregateId", UUID.randomUUID()).param("payload", "{\"n\":" + second + "}")
                .param("occurredAt", Timestamp.from(T0.plusSeconds(second))).update();
        return id;
    }

    private static Set<UUID> ids(List<OutboxMessage> messages) {
        return messages.stream().map(OutboxMessage::eventId).collect(Collectors.toSet());
    }

    private static PublicationResult confirmAll(List<OutboxMessage> messages) {
        return PublicationResult.allConfirmed(ids(messages));
    }

    private long published() {
        return jdbc.sql("SELECT count(*) FROM outbox_events WHERE published_at IS NOT NULL").query(Long.class).single();
    }

    @Test
    @DisplayName("marca como publicado solo lo confirmado; lo demás suma un intento con el motivo y sigue pendiente")
    void marksOnlyConfirmed() {
        UUID first = pendingEvent(1);
        UUID second = pendingEvent(2);

        OutboxBatch batch = outbox.publishPending(10,
                messages -> PublicationResult.partial(Set.of(first), "RabbitMQ no confirmó a tiempo"));

        assertThat(batch.messages()).extracting(OutboxMessage::eventId).containsExactly(first, second);
        assertThat(jdbc.sql("SELECT published_at IS NOT NULL FROM outbox_events WHERE id = :id").param("id", first)
                .query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT attempts, last_error, published_at FROM outbox_events WHERE id = :id")
                .param("id", second).query().singleRow())
                .containsEntry("attempts", 1).containsEntry("last_error", "RabbitMQ no confirmó a tiempo")
                .containsEntry("published_at", null);

        OutboxBatch retry = outbox.publishPending(10, JpaOutboxAdapterTest::confirmAll);

        assertThat(retry.messages()).extracting(OutboxMessage::eventId).containsExactly(second);
        assertThat(published()).isEqualTo(2);
        assertThat(outbox.publishPending(10, JpaOutboxAdapterTest::confirmAll).messages()).isEmpty();
    }

    @Test
    @DisplayName("una petición rechazada se guarda sola en el outbox, sin factura, lista para publicarse")
    void appendsRequestRejected() {
        outbox.append(new InvoiceEvent.InvoiceRequestRejected("VENTA-1", null, null, "motivo"));

        assertThat(jdbc.sql("SELECT aggregate_type, aggregate_id, event_type, routing_key FROM outbox_events")
                .query().singleRow())
                .containsEntry("aggregate_type", "InvoiceRequest").containsEntry("aggregate_id", null)
                .containsEntry("event_type", "InvoiceRequestRejected")
                .containsEntry("routing_key", "invoice-request.rejected.v1");
        assertThat(outbox.publishPending(10, JpaOutboxAdapterTest::confirmAll).messages()).hasSize(1);
    }

    @Test
    @DisplayName("informa cuántos eventos esperan y desde cuándo el más antiguo")
    void status() {
        assertThat(outbox.status()).isEqualTo(new OutboxStatus(0, Optional.empty()));
        pendingEvent(5);
        pendingEvent(3);

        assertThat(outbox.status().pending()).isEqualTo(2);
        assertThat(outbox.status().oldestPendingSince()).contains(T0.plusSeconds(3));
    }

    @Test
    @DisplayName("dos publicadores a la vez toman lotes distintos (SKIP LOCKED): ningún evento se publica dos veces")
    void concurrentPublishersNeverTakeTheSameEvent() throws Exception {
        Set<UUID> all = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            all.add(pendingEvent(i));
        }
        List<UUID> sentByA = new ArrayList<>();
        List<UUID> sentByB = new ArrayList<>();
        CountDownLatch aHoldsItsBatch = new CountDownLatch(1);
        CountDownLatch bFinished = new CountDownLatch(1);

        // A bloquea su lote y no termina su transacción hasta que B haya publicado el suyo.
        CompletableFuture<OutboxBatch> a = CompletableFuture.supplyAsync(() -> outbox.publishPending(5, messages -> {
            sentByA.addAll(ids(messages));
            aHoldsItsBatch.countDown();
            await(bFinished);
            return confirmAll(messages);
        }));
        assertThat(aHoldsItsBatch.await(10, TimeUnit.SECONDS)).isTrue();

        // B pide hasta 10 mientras A tiene 5 bloqueados: se los salta (no espera) y toma los otros 5.
        OutboxBatch b = CompletableFuture.supplyAsync(() -> outbox.publishPending(10, messages -> {
            sentByB.addAll(ids(messages));
            return confirmAll(messages);
        })).get(10, TimeUnit.SECONDS);
        bFinished.countDown();
        a.get(10, TimeUnit.SECONDS);

        assertThat(sentByA).hasSize(5);
        assertThat(b.messages()).hasSize(5);
        assertThat(sentByA).doesNotContainAnyElementsOf(sentByB);
        List<UUID> sent = new ArrayList<>(sentByA);
        sent.addAll(sentByB);
        assertThat(sent).as("cada evento, exactamente una vez").doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(all);
        assertThat(published()).isEqualTo(10);
        assertThat(outbox.publishPending(10, JpaOutboxAdapterTest::confirmAll).messages()).isEmpty();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("El otro publicador no terminó a tiempo");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}

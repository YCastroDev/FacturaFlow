package com.facturaflow.invoicing.infrastructure.adapter.out.persistence;

import static com.facturaflow.invoicing.domain.model.CreditNoteFixtures.pendingInvoiceToCredit;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.electronicDocument;
import static org.assertj.core.api.Assertions.assertThat;

import com.facturaflow.invoicing.TestcontainersConfiguration;
import com.facturaflow.invoicing.application.exception.InvoiceConcurrentModificationException;
import com.facturaflow.invoicing.application.port.in.CreateCreditNoteCommand;
import com.facturaflow.invoicing.application.port.in.CreateCreditNoteUseCase;
import com.facturaflow.invoicing.application.port.out.InvoiceRepository;
import com.facturaflow.invoicing.application.port.out.RequestHash;
import com.facturaflow.invoicing.domain.exception.InvalidCreditNoteException;
import com.facturaflow.invoicing.domain.model.CreditNoteReason;
import com.facturaflow.invoicing.domain.model.Invoice;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * La regla que cruza agregados bajo concurrencia REAL (ADR 0009): varios hilos crean notas a la vez
 * sobre la misma factura, con el caso de uso, el adaptador JPA y PostgreSQL de verdad. Pase lo que
 * pase con cada petición (creada, 422 porque ya no cabe, o 409 por conflictos persistentes), lo
 * acreditado en la base nunca supera lo facturado.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ConcurrentCreditNotesTest {

    private static final int THREADS = 8;

    @Autowired
    private CreateCreditNoteUseCase createCreditNote;

    @Autowired
    private InvoiceRepository invoices;

    @Autowired
    private JdbcClient jdbc;

    private Invoice invoice;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE TABLE invoices CASCADE").update();
        Invoice saved = invoices.saveNew(pendingInvoiceToCredit(), new RequestHash("a".repeat(64)));
        saved.markAsIssued(electronicDocument("147475.01"));
        invoice = invoices.save(saved);
    }

    private record Outcome(int created, int rejected422, int conflicts409) {
    }

    /** Lanza {@value #THREADS} creaciones a la vez (misma barrera de salida), cada una por {@code quantity} de la línea 1. */
    private Outcome raceFor(String quantity) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            String referenceCode = "DEV-RACE-" + i;
            tasks.add(() -> {
                start.await();
                try {
                    createCreditNote.create(new CreateCreditNoteCommand(invoice.id().value(), referenceCode,
                            CreditNoteReason.PARTIAL_RETURN,
                            List.of(new CreateCreditNoteCommand.Line(1, new BigDecimal(quantity))), null));
                    return "created";
                } catch (InvalidCreditNoteException e) {
                    return "422";
                } catch (InvoiceConcurrentModificationException e) {
                    return "409";
                }
            });
        }
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<String>> futures = tasks.stream().map(pool::submit).toList();
            start.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));   // cualquier otra excepción hace fallar el test
            }
            return new Outcome((int) results.stream().filter("created"::equals).count(),
                    (int) results.stream().filter("422"::equals).count(),
                    (int) results.stream().filter("409"::equals).count());
        } finally {
            pool.shutdownNow();
        }
    }

    private BigDecimal creditedInDatabase() {
        return jdbc.sql("""
                SELECT coalesce(sum(l.quantity), 0) FROM credit_note_lines l
                JOIN credit_notes n ON n.id = l.credit_note_id
                WHERE n.invoice_id = ? AND l.invoice_line_number = 1 AND n.status <> 'REJECTED'
                """).param(invoice.id().value()).query(BigDecimal.class).single();
    }

    @RepeatedTest(3)
    @DisplayName("8 notas simultáneas de 1 unidad sobre una línea de 3: se crean como máximo 3 y nunca se acredita de más")
    void neverCreditsMoreThanInvoiced() throws Exception {
        Outcome outcome = raceFor("1");

        assertThat(creditedInDatabase()).isLessThanOrEqualTo(new BigDecimal("3"));
        assertThat(creditedInDatabase()).isEqualByComparingTo(BigDecimal.valueOf(outcome.created()));
        assertThat(outcome.created()).isBetween(1, 3);
        assertThat(outcome.created() + outcome.rejected422() + outcome.conflicts409()).isEqualTo(THREADS);
        assertThat(jdbc.sql("SELECT count(*) FROM credit_notes").query(Long.class).single())
                .isEqualTo(outcome.created());
    }

    @RepeatedTest(3)
    @DisplayName("8 notas simultáneas de 2 unidades sobre una línea de 3: solo una puede crearse")
    void onlyOneOfTheBigNotesFits() throws Exception {
        Outcome outcome = raceFor("2");

        assertThat(outcome.created()).isEqualTo(1);
        assertThat(creditedInDatabase()).isEqualByComparingTo("2");
    }
}

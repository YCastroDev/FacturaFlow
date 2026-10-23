package com.facturaflow.catalog.integration;

import static com.facturaflow.catalog.model.CatalogFixtures.coffee;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.facturaflow.catalog.TestcontainersConfiguration;
import com.facturaflow.catalog.model.Product;
import com.facturaflow.catalog.model.ProductDetails;
import com.facturaflow.catalog.model.UnitOfMeasure;
import com.facturaflow.catalog.repository.ProductRepository;
import com.facturaflow.catalog.service.DuplicateProductCodeException;
import com.facturaflow.catalog.service.ProductService;
import com.facturaflow.catalog.service.StaleVersionException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Concurrencia REAL contra PostgreSQL: varias transacciones a la vez sobre el mismo registro. Sin
 * {@code @Transactional} en el test: cada operación confirma su propia transacción, como en producción.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ConcurrencyIntegrationTest {

    private static final int THREADS = 8;

    @Autowired
    private ProductService service;

    @Autowired
    private ProductRepository repository;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void clean() {
        CatalogDatabase.clean(jdbc);
    }

    private static ProductDetails named(String name) {
        return new ProductDetails(name, new BigDecimal("1000"), UnitOfMeasure.UNIT, false, List.of());
    }

    /** Lanza las tareas a la vez (todas esperan la misma señal) y devuelve el resultado o la excepción de cada una. */
    private static List<Object> runConcurrently(List<Callable<Object>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        return task.call();
                    } catch (RuntimeException e) {
                        return e;
                    }
                }));
            }
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("8 PUT simultáneos con el mismo If-Match: exactamente uno gana y los demás reciben 412")
    void concurrentUpdatesWithSameVersion() throws Exception {
        UUID id = service.create("CAFE-500", coffee()).id();

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            String name = "Nombre " + i;
            tasks.add(() -> service.update(id, 0, "CAFE-500", named(name)));
        }
        List<Object> results = runConcurrently(tasks);

        List<Object> winners = results.stream().filter(Product.class::isInstance).toList();
        assertThat(winners).hasSize(1);
        assertThat(results).filteredOn(r -> !(r instanceof Product)).hasSize(THREADS - 1)
                .allMatch(StaleVersionException.class::isInstance, "todos los perdedores son 412");
        Product stored = service.get(id);
        assertThat(stored.version()).isEqualTo(1);
        assertThat(stored.details().name()).isEqualTo(((Product) winners.getFirst()).details().name());
    }

    @Test
    @DisplayName("la red de seguridad: si otra transacción confirma entre la lectura y el UPDATE, JPA lo detecta")
    void optimisticLockDetectsTheRace() throws Exception {
        UUID id = service.create("CAFE-500", coffee()).id();
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch otherCommitted = new CountDownLatch(1);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> slow = executor.submit(() -> transactions.executeWithoutResult(status -> {
                Product product = repository.findById(id).orElseThrow();   // lee la versión 0
                read.countDown();
                await(otherCommitted);
                product.update("CAFE-500", named("Lento"), Instant.now());
                repository.saveAndFlush(product);                         // UPDATE ... WHERE version = 0
            }));
            await(read);
            service.update(id, 0, "CAFE-500", named("Rápido"));            // confirma la versión 1
            otherCommitted.countDown();

            assertThatThrownBy(() -> slow.get(30, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ObjectOptimisticLockingFailureException.class);
        } finally {
            executor.shutdownNow();
        }
        assertThat(service.get(id).details().name()).as("no hubo actualización perdida").isEqualTo("Rápido");
    }

    @Test
    @DisplayName("8 creaciones simultáneas del mismo código: una se crea y las demás son 409, nunca un 500")
    void concurrentCreatesWithSameCode() throws Exception {
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            String code = i % 2 == 0 ? "DUP-1" : "dup-1";
            tasks.add(() -> service.create(code, coffee()));
        }
        List<Object> results = runConcurrently(tasks);

        assertThat(results).filteredOn(Product.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(r -> !(r instanceof Product))
                .allMatch(DuplicateProductCodeException.class::isInstance, "todos los perdedores son 409");
        assertThat(jdbc.sql("SELECT count(*) FROM products").query(Long.class).single()).isEqualTo(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}

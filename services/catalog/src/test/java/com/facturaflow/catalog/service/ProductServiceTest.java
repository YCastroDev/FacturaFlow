package com.facturaflow.catalog.service;

import static com.facturaflow.catalog.model.CatalogFixtures.NOW;
import static com.facturaflow.catalog.model.CatalogFixtures.coffee;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.facturaflow.catalog.model.CatalogRuleException;
import com.facturaflow.catalog.model.Product;
import com.facturaflow.catalog.repository.ProductRepository;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

/** El servicio sin Spring ni base: repositorio simulado y reloj fijo. */
class ProductServiceTest {

    private final ProductRepository repository = mock(ProductRepository.class);
    private final ProductService service = new ProductService(repository, Clock.fixed(NOW, ZoneOffset.UTC));

    private static Product stored(long version) {
        Product product = new Product("CAFE-500", coffee(), NOW.minusSeconds(3600));
        ReflectionTestUtils.setField(product, "version", version);
        return product;
    }

    private static DataIntegrityViolationException violationOf(String constraint) {
        return new DataIntegrityViolationException("duplicada",
                new ConstraintViolationException("duplicada", new SQLException("23505"), constraint));
    }

    @Test
    @DisplayName("crear guarda el producto con la fecha del reloj")
    void createSavesWithClockDate() {
        given(repository.findByCodeIgnoringCase("CAFE-500")).willReturn(Optional.empty());
        given(repository.saveAndFlush(any(Product.class))).willAnswer(call -> call.getArgument(0));

        Product product = service.create(" CAFE-500 ", coffee());

        assertThat(product.code()).isEqualTo("CAFE-500");
        assertThat(product.createdAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("crear un código que ya existe (en otras mayúsculas) es un conflicto y no guarda nada")
    void createDuplicateCode() {
        given(repository.findByCodeIgnoringCase("cafe-500")).willReturn(Optional.of(stored(0)));

        assertThatThrownBy(() -> service.create("cafe-500", coffee()))
                .isInstanceOf(DuplicateProductCodeException.class).hasMessageContaining("cafe-500");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("si otra petición crea el mismo código entre la comprobación y el INSERT, también es 409")
    void createRaceOnUniqueIndex() {
        given(repository.findByCodeIgnoringCase("CAFE-500")).willReturn(Optional.empty());
        given(repository.saveAndFlush(any(Product.class))).willThrow(violationOf("uq_products_code"));

        assertThatThrownBy(() -> service.create("CAFE-500", coffee())).isInstanceOf(DuplicateProductCodeException.class);
    }

    @Test
    @DisplayName("otra falla de integridad no se disfraza de duplicado")
    void otherIntegrityFailuresPropagate() {
        given(repository.findByCodeIgnoringCase("CAFE-500")).willReturn(Optional.empty());
        given(repository.saveAndFlush(any(Product.class))).willThrow(violationOf("ck_products_unit_price"));

        assertThatThrownBy(() -> service.create("CAFE-500", coffee()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("actualizar con la versión actual guarda los cambios")
    void updateWithCurrentVersion() {
        Product product = stored(3);
        UUID id = product.id();
        given(repository.findById(id)).willReturn(Optional.of(product));

        Product updated = service.update(id, 3, "CAFE-500", coffee("19900"));

        assertThat(updated.details().unitPrice()).isEqualByComparingTo("19900");
        assertThat(updated.updatedAt()).isEqualTo(NOW);
        verify(repository).saveAndFlush(product);
    }

    @Test
    @DisplayName("actualizar con una versión vieja (If-Match) es 412 y no modifica nada")
    void updateWithStaleVersion() {
        Product product = stored(4);
        given(repository.findById(product.id())).willReturn(Optional.of(product));

        assertThatThrownBy(() -> service.update(product.id(), 3, "CAFE-500", coffee("19900")))
                .isInstanceOf(StaleVersionException.class);
        assertThat(product.details()).isEqualTo(coffee());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("si otra transacción gana la carrera (bloqueo optimista al guardar), también es 412")
    void updateLosingTheRace() {
        Product product = stored(3);
        given(repository.findById(product.id())).willReturn(Optional.of(product));
        given(repository.saveAndFlush(product))
                .willThrow(new ObjectOptimisticLockingFailureException(Product.class, product.id()));

        assertThatThrownBy(() -> service.update(product.id(), 3, "CAFE-500", coffee("19900")))
                .isInstanceOf(StaleVersionException.class);
    }

    @Test
    @DisplayName("un PUT idéntico no escribe (y no sube la versión)")
    void identicalUpdateDoesNotWrite() {
        Product product = stored(3);
        given(repository.findById(product.id())).willReturn(Optional.of(product));

        service.update(product.id(), 3, "CAFE-500", coffee());

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("cambiar el código es una regla incumplida (422), no un conflicto de versión")
    void codeChangeIsRejected() {
        Product product = stored(3);
        given(repository.findById(product.id())).willReturn(Optional.of(product));

        assertThatThrownBy(() -> service.update(product.id(), 3, "OTRO", coffee()))
                .isInstanceOf(CatalogRuleException.class);
    }

    @Test
    @DisplayName("desactivar sin If-Match y perder la carrera es 409 (no 412: no hubo precondición)")
    void deactivateWithoutPreconditionLosingTheRace() {
        Product product = stored(3);
        given(repository.findById(product.id())).willReturn(Optional.of(product));
        given(repository.saveAndFlush(product))
                .willThrow(new ObjectOptimisticLockingFailureException(Product.class, product.id()));

        assertThatThrownBy(() -> service.deactivate(product.id(), null)).isInstanceOf(ConcurrentUpdateException.class);
    }

    @Test
    @DisplayName("desactivar con If-Match viejo es 412; desactivar uno inactivo no escribe")
    void deactivateChecksOptionalVersion() {
        Product product = stored(3);
        given(repository.findById(product.id())).willReturn(Optional.of(product));

        assertThatThrownBy(() -> service.deactivate(product.id(), 2L)).isInstanceOf(StaleVersionException.class);
        service.deactivate(product.id(), 3L);
        service.deactivate(product.id(), null);

        assertThat(product.active()).isFalse();
        verify(repository).saveAndFlush(product);   // una sola vez
    }

    @Test
    @DisplayName("un id inexistente es 404")
    void notFound() {
        UUID id = UUID.randomUUID();
        given(repository.findById(id)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id)).isInstanceOf(ProductNotFoundException.class).hasMessageContaining(id.toString());
        assertThatThrownBy(() -> service.getByCode("NO-EXISTE")).isInstanceOf(ProductNotFoundException.class);
    }
}

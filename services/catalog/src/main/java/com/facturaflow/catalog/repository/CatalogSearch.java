package com.facturaflow.catalog.repository;

import com.facturaflow.catalog.model.Customer;
import com.facturaflow.catalog.model.Product;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

/**
 * Filtros del listado (ADR 0011): texto libre opcional (contiene, sin distinguir mayúsculas) y estado
 * opcional. El orden es fijo y estable (con el id como desempate) para que la paginación no repita ni
 * salte filas: productos por código, clientes por nombre visible.
 *
 * <p>{@code lower(...) LIKE '%texto%'} recorre la tabla; basta para un catálogo de pyme. En producción
 * se usaría pg_trgm con índices GIN (y unaccent para ignorar tildes).
 */
public final class CatalogSearch {

    private static final char ESCAPE = '\\';

    private CatalogSearch() {
    }

    /** Busca en código y nombre. {@code text} y {@code active} nulos = sin ese filtro. */
    public static Specification<Product> products(String text, Boolean active) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (active != null) {
                predicates.add(cb.equal(root.get("active"), active));
            }
            String pattern = pattern(text);
            if (pattern != null) {
                predicates.add(cb.or(contains(cb, root.get("code"), pattern), contains(cb, root.get("name"), pattern)));
            }
            // Spring Data quita este orden en la consulta de conteo de la página.
            query.orderBy(cb.asc(cb.lower(root.get("code"))), cb.asc(root.get("id")));
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    /** Busca en número de documento, razón social, nombres y email. */
    public static Specification<Customer> customers(String text, Boolean active) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (active != null) {
                predicates.add(cb.equal(root.get("active"), active));
            }
            String pattern = pattern(text);
            if (pattern != null) {
                predicates.add(cb.or(
                        contains(cb, root.get("documentNumber"), pattern),
                        contains(cb, root.get("companyName"), pattern),
                        contains(cb, root.get("names"), pattern),
                        contains(cb, root.get("email"), pattern)));
            }
            Expression<String> displayName = cb.coalesce(root.get("companyName"), root.get("names"));
            query.orderBy(cb.asc(cb.lower(displayName)), cb.asc(root.get("id")));
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static Predicate contains(CriteriaBuilder cb, Expression<String> field, String pattern) {
        return cb.like(cb.lower(field), pattern, ESCAPE);
    }

    /** {@code %texto%} en minúsculas; '%', '_' y '\' del usuario se buscan literalmente. */
    static String pattern(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String escaped = text.strip().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }
}

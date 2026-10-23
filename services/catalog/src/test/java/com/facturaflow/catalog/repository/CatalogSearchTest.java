package com.facturaflow.catalog.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CatalogSearchTest {

    @Test
    @DisplayName("el texto se busca como 'contiene', en minúsculas y sin espacios a los lados")
    void containsPattern() {
        assertThat(CatalogSearch.pattern("  Café ")).isEqualTo("%café%");
    }

    @Test
    @DisplayName("sin texto no hay filtro")
    void blankMeansNoFilter() {
        assertThat(CatalogSearch.pattern(null)).isNull();
        assertThat(CatalogSearch.pattern("   ")).isNull();
    }

    @Test
    @DisplayName("los comodines del usuario (%, _ y \\) se buscan literalmente")
    void wildcardsAreEscaped() {
        assertThat(CatalogSearch.pattern("50%_a\\b")).isEqualTo("%50\\%\\_a\\\\b%");
    }
}

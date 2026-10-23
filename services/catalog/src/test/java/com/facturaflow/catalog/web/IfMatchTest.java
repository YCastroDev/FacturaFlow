package com.facturaflow.catalog.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IfMatchTest {

    @Test
    @DisplayName("If-Match es el ETag entre comillas; por comodidad también se acepta el número solo")
    void parsesStrongEtag() {
        assertThat(IfMatch.required("\"3\"")).isEqualTo(3);
        assertThat(IfMatch.required(" \"12\" ")).isEqualTo(12);
        assertThat(IfMatch.required("0")).isZero();
        assertThat(IfMatch.etag(3)).isEqualTo("\"3\"");
    }

    @Test
    @DisplayName("en PUT el header es obligatorio (428); en activar y desactivar es opcional")
    void requiredOrOptional() {
        assertThatThrownBy(() -> IfMatch.required(null)).isInstanceOf(PreconditionRequiredException.class);
        assertThatThrownBy(() -> IfMatch.required(" ")).isInstanceOf(PreconditionRequiredException.class);
        assertThat(IfMatch.optional(null)).isNull();
        assertThat(IfMatch.optional("\"5\"")).isEqualTo(5L);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"*", "W/\"3\"", "\"3\", \"4\"", "\"3", "abc", "\"-1\"", "1234567890123456789"})
    @DisplayName("se rechazan '*', ETags débiles, listas y valores que no son una versión")
    void rejectsOtherForms(String header) {
        assertThatThrownBy(() -> IfMatch.required(header)).isInstanceOf(InvalidIfMatchException.class);
    }
}

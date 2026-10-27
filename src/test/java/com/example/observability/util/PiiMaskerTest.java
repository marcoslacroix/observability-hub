package com.example.observability.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PiiMaskerTest {

    @Test
    @DisplayName("Deve mascarar email mantendo formato básico e protegendo dados pessoais")
    void shouldMaskEmailProperly() {
        assertEquals("u***o@example.com", PiiMasker.maskEmail("usuario@example.com"));
        assertEquals("a*@example.com", PiiMasker.maskEmail("ab@example.com"));
        assertEquals("*@example.com", PiiMasker.maskEmail("a@example.com"));
        assertEquals("[vazio]", PiiMasker.maskEmail(null));
        assertEquals("[vazio]", PiiMasker.maskEmail("   "));
        assertEquals("***", PiiMasker.maskEmail("invalid-email"));
    }

    @Test
    @DisplayName("Deve mascarar nomes preservando apenas as iniciais")
    void shouldMaskNameProperly() {
        assertEquals("M***", PiiMasker.maskName("Marcos"));
        assertEquals("M*** L***", PiiMasker.maskName("Marcos Lacroix"));
        assertEquals("M*** D*** L***", PiiMasker.maskName("Marcos Daniel Lacroix"));
        assertEquals("[vazio]", PiiMasker.maskName(null));
        assertEquals("[vazio]", PiiMasker.maskName("   "));
    }
}

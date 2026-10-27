package com.example.observability.dto;

import com.example.observability.util.PiiMasker;

public record UserDto(String name, String email) {

    /**
     * Representação textual mascarada para evitar vazamento de PII em logs (LGPD).
     */
    @Override
    public String toString() {
        return "UserDto[name=" + PiiMasker.maskName(name) + ", email=" + PiiMasker.maskEmail(email) + "]";
    }
}

package com.example.observability.dto;

import com.example.observability.util.PiiMasker;

import java.time.Instant;

public record UserRegisteredEvent(
        String eventId,
        String name,
        String email,
        Instant registeredAt
) {

    /**
     * Retorna uma cópia do evento com dados pessoais anonimizados/mascarados para exposição pública ou logs.
     */
    public UserRegisteredEvent toMasked() {
        return new UserRegisteredEvent(
                eventId,
                PiiMasker.maskName(name),
                PiiMasker.maskEmail(email),
                registeredAt
        );
    }

    /**
     * Representação textual mascarada para evitar vazamento de PII em logs (LGPD).
     */
    @Override
    public String toString() {
        return "UserRegisteredEvent[eventId=" + eventId +
                ", name=" + PiiMasker.maskName(name) +
                ", email=" + PiiMasker.maskEmail(email) +
                ", registeredAt=" + registeredAt + "]";
    }
}

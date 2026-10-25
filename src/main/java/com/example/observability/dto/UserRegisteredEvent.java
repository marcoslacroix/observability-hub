package com.example.observability.dto;

import java.time.Instant;

public record UserRegisteredEvent(
        String eventId,
        String name,
        String email,
        Instant registeredAt
) {
}

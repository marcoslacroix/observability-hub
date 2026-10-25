package com.example.observability.dto;

public record KafkaMessageRequest(
        String key,
        String message
) {
}

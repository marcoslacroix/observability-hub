package com.example.observability;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component("customServiceHealth") // Nome que aparecerá no Actuator
public class CustomServiceHealthIndicator implements HealthIndicator {

    private final RestTemplate restTemplate;

    public CustomServiceHealthIndicator(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public Health health() {
        try {
            // Exemplo: Validando uma API externa ou dependência crítica
            String externalApiUrl = "https://api.exemplo.com/health";
            restTemplate.getForEntity(externalApiUrl, Void.class);

            // Se chegou até aqui sem exceção, está tudo funcionando
            return Health.up()
                    .withDetail("service", "API Externa")
                    .withDetail("status", "Disponível")
                    .build();

        } catch (Exception e) {
            // Em caso de falha, retorne DOWN e adicione detalhes do erro
            return Health.down()
                    .withDetail("service", "API Externa")
                    .withDetail("erro", e.getMessage())
                    .build();
        }
    }
}

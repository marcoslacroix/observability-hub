package com.example.observability.service;

import com.example.observability.dto.UserDto;
import com.example.observability.dto.UserRegisteredEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

@Service
@Log4j2
public class KafkaProducerService {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final JsonMapper jsonMapper;
    private final Counter publishedMessagesCounter;
    private final String userRegistrationsTopic;
    private final String customEventsTopic;

    public KafkaProducerService(
            KafkaTemplate<String, String> kafkaTemplate,
            JsonMapper jsonMapper,
            MeterRegistry meterRegistry,
            @Value("${app.kafka.topics.user-registrations}") String userRegistrationsTopic,
            @Value("${app.kafka.topics.custom-events}") String customEventsTopic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.jsonMapper = jsonMapper;
        this.userRegistrationsTopic = userRegistrationsTopic;
        this.customEventsTopic = customEventsTopic;
        this.publishedMessagesCounter = Counter.builder("kafka.messages.published.total")
                .description("Número total de mensagens publicadas no Kafka pela aplicação")
                .register(meterRegistry);
    }

    public UserRegisteredEvent publishUserRegisteredEvent(UserDto userDto) {
        UserRegisteredEvent event = new UserRegisteredEvent(
                UUID.randomUUID().toString(),
                userDto.name(),
                userDto.email(),
                Instant.now()
        );

        String payload = jsonMapper.writeValueAsString(event);
        log.info("Publicando evento de utilizador registado no tópico [{}]: key={}, payload={}",
                userRegistrationsTopic, userDto.email(), payload);

        kafkaTemplate.send(userRegistrationsTopic, userDto.email(), payload)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Falha ao publicar evento no tópico [{}]: {}", userRegistrationsTopic, ex.getMessage(), ex);
                    } else {
                        publishedMessagesCounter.increment();
                        log.info("Evento publicado com sucesso no tópico [{}], partição={}, offset={}",
                                result.getRecordMetadata().topic(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });

        return event;
    }

    public void publishCustomEvent(String key, String message) {
        String messageKey = (key == null || key.isBlank()) ? UUID.randomUUID().toString() : key;
        log.info("Publicando mensagem customizada no tópico [{}]: key={}, message={}",
                customEventsTopic, messageKey, message);

        kafkaTemplate.send(customEventsTopic, messageKey, message)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Falha ao publicar mensagem no tópico [{}]: {}", customEventsTopic, ex.getMessage(), ex);
                    } else {
                        publishedMessagesCounter.increment();
                        log.info("Mensagem publicada com sucesso no tópico [{}], partição={}, offset={}",
                                result.getRecordMetadata().topic(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }

}

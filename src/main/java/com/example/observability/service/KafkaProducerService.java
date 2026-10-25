package com.example.observability.service;

import com.example.observability.dto.UserDto;
import com.example.observability.dto.UserRegisteredEvent;
import com.example.observability.exception.BusinessValidationException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
    private final MeterRegistry meterRegistry;
    private final Counter publishedSuccessCounter;
    private final Counter publishedFailureCounter;
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
        this.meterRegistry = meterRegistry;
        this.userRegistrationsTopic = userRegistrationsTopic;
        this.customEventsTopic = customEventsTopic;

        this.publishedSuccessCounter = Counter.builder("kafka.messages.published.total")
                .description("Número total de mensagens publicadas com sucesso no Kafka pela aplicação")
                .tag("status", "success")
                .register(meterRegistry);

        this.publishedFailureCounter = Counter.builder("kafka.messages.published.total")
                .description("Número total de falhas ao publicar mensagens no Kafka pela aplicação")
                .tag("status", "failure")
                .register(meterRegistry);
    }

    public UserRegisteredEvent publishUserRegisteredEvent(UserDto userDto) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
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
                            publishedFailureCounter.increment();
                            sample.stop(Timer.builder("kafka.producer.processing.time")
                                    .description("Tempo de processamento e confirmação (ACK) de publicação no Kafka")
                                    .tag("topic", userRegistrationsTopic)
                                    .tag("status", "failure")
                                    .register(meterRegistry));
                            log.error("Falha ao publicar evento no tópico [{}]: {}", userRegistrationsTopic, ex.getMessage(), ex);
                        } else {
                            publishedSuccessCounter.increment();
                            sample.stop(Timer.builder("kafka.producer.processing.time")
                                    .description("Tempo de processamento e confirmação (ACK) de publicação no Kafka")
                                    .tag("topic", userRegistrationsTopic)
                                    .tag("status", "success")
                                    .register(meterRegistry));
                            log.info("Evento publicado com sucesso no tópico [{}], partição={}, offset={}",
                                    result.getRecordMetadata().topic(),
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset());
                        }
                    });

            return event;
        } catch (Exception e) {
            publishedFailureCounter.increment();
            sample.stop(Timer.builder("kafka.producer.processing.time")
                    .description("Tempo de processamento e confirmação (ACK) de publicação no Kafka")
                    .tag("topic", userRegistrationsTopic)
                    .tag("status", "failure")
                    .register(meterRegistry));
            throw e;
        }
    }

    public void publishCustomEvent(String key, String message) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            if (message == null || message.isBlank()) {
                throw new BusinessValidationException("A mensagem para publicação no Kafka não pode ser vazia");
            }
            String messageKey = (key == null || key.isBlank()) ? UUID.randomUUID().toString() : key;
            log.info("Publicando mensagem customizada no tópico [{}]: key={}, message={}",
                    customEventsTopic, messageKey, message);

            kafkaTemplate.send(customEventsTopic, messageKey, message)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            publishedFailureCounter.increment();
                            sample.stop(Timer.builder("kafka.producer.processing.time")
                                    .description("Tempo de processamento e confirmação (ACK) de publicação no Kafka")
                                    .tag("topic", customEventsTopic)
                                    .tag("status", "failure")
                                    .register(meterRegistry));
                            log.error("Falha ao publicar mensagem no tópico [{}]: {}", customEventsTopic, ex.getMessage(), ex);
                        } else {
                            publishedSuccessCounter.increment();
                            sample.stop(Timer.builder("kafka.producer.processing.time")
                                    .description("Tempo de processamento e confirmação (ACK) de publicação no Kafka")
                                    .tag("topic", customEventsTopic)
                                    .tag("status", "success")
                                    .register(meterRegistry));
                            log.info("Mensagem publicada com sucesso no tópico [{}], partição={}, offset={}",
                                    result.getRecordMetadata().topic(),
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset());
                        }
                    });
        } catch (Exception e) {
            publishedFailureCounter.increment();
            sample.stop(Timer.builder("kafka.producer.processing.time")
                    .description("Tempo de processamento e confirmação (ACK) de publicação no Kafka")
                    .tag("topic", customEventsTopic)
                    .tag("status", "failure")
                    .register(meterRegistry));
            throw e;
        }
    }

}

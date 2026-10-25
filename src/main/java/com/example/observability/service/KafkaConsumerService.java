package com.example.observability.service;

import com.example.observability.dto.UserRegisteredEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.log4j.Log4j2;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Log4j2
public class KafkaConsumerService {

    private static final int MAX_STORED_EVENTS = 200;

    private final JsonMapper jsonMapper;
    private final Counter consumedMessagesCounter;
    private final Deque<UserRegisteredEvent> consumedUserEvents = new ConcurrentLinkedDeque<>();
    private final AtomicInteger userEventsCount = new AtomicInteger(0);
    private final Deque<Map<String, Object>> consumedCustomEvents = new ConcurrentLinkedDeque<>();
    private final AtomicInteger customEventsCount = new AtomicInteger(0);

    public KafkaConsumerService(JsonMapper jsonMapper, MeterRegistry meterRegistry) {
        this.jsonMapper = jsonMapper;
        this.consumedMessagesCounter = Counter.builder("kafka.messages.consumed.total")
                .description("Número total de mensagens consumidas do Kafka pela aplicação")
                .register(meterRegistry);
    }

    @KafkaListener(topics = "${app.kafka.topics.user-registrations}", groupId = "${spring.kafka.consumer.group-id}")
    public void consumeUserRegisteredEvent(ConsumerRecord<String, String> record) {
        log.info("Consumindo evento de registo do tópico [{}], partição={}, offset={}, key={}, payload={}",
                record.topic(), record.partition(), record.offset(), record.key(), record.value());

        UserRegisteredEvent event = jsonMapper.readValue(record.value(), UserRegisteredEvent.class);
        consumedUserEvents.addLast(event);
        if (userEventsCount.incrementAndGet() > MAX_STORED_EVENTS && consumedUserEvents.pollFirst() != null) {
            userEventsCount.decrementAndGet();
        }
        consumedMessagesCounter.increment();

        log.info("Evento de utilizador processado pelo consumer com sucesso: eventId={}, email={}",
                event.eventId(), event.email());
    }

    @KafkaListener(topics = "${app.kafka.topics.custom-events}", groupId = "${spring.kafka.consumer.group-id}")
    public void consumeCustomEvent(ConsumerRecord<String, String> record) {
        log.info("Consumindo evento customizado do tópico [{}], partição={}, offset={}, key={}, value={}",
                record.topic(), record.partition(), record.offset(), record.key(), record.value());

        consumedCustomEvents.addLast(Map.of(
                "topic", record.topic(),
                "partition", record.partition(),
                "offset", record.offset(),
                "key", record.key() != null ? record.key() : "",
                "message", record.value() != null ? record.value() : "",
                "consumedAt", Instant.now().toString()
        ));
        if (customEventsCount.incrementAndGet() > MAX_STORED_EVENTS && consumedCustomEvents.pollFirst() != null) {
            customEventsCount.decrementAndGet();
        }
        consumedMessagesCounter.increment();
    }

    public List<UserRegisteredEvent> getConsumedUserEvents() {
        return List.copyOf(consumedUserEvents);
    }

    public List<Map<String, Object>> getConsumedCustomEvents() {
        return List.copyOf(consumedCustomEvents);
    }

}

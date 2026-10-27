package com.example.observability.controller;

import com.example.observability.dto.KafkaMessageRequest;
import com.example.observability.service.KafkaConsumerService;
import com.example.observability.service.KafkaProducerService;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/kafka")
@Log4j2
public class KafkaController {

    private final KafkaProducerService kafkaProducerService;
    private final KafkaConsumerService kafkaConsumerService;

    public KafkaController(KafkaProducerService kafkaProducerService, KafkaConsumerService kafkaConsumerService) {
        this.kafkaProducerService = kafkaProducerService;
        this.kafkaConsumerService = kafkaConsumerService;
    }

    @PostMapping("/publish")
    public ResponseEntity<Map<String, String>> publishMessage(@RequestBody KafkaMessageRequest request) {
        log.info("Recebida requisição para publicar mensagem no Kafka: key={}", request != null ? request.key() : null);
        kafkaProducerService.publishCustomEvent(request != null ? request.key() : null, request != null ? request.message() : null);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of(
                        "status", "accepted",
                        "message", "Mensagem enviada para o Kafka com sucesso"
                ));
    }

    @GetMapping("/consumed")
    public ResponseEntity<Map<String, Object>> getConsumedMessages() {
        return ResponseEntity.ok(Map.of(
                "userRegistrationEvents", kafkaConsumerService.getConsumedUserEvents(),
                "customEvents", kafkaConsumerService.getConsumedCustomEvents()
        ));
    }

}

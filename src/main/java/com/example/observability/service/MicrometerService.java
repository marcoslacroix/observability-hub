package com.example.observability.service;

import com.example.observability.dto.TaxResult;
import com.example.observability.dto.UserDto;
import com.example.observability.exception.BusinessValidationException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.SneakyThrows;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Deque;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class MicrometerService {

    private static final int MAX_STORED_USERS = 200;

    private final MeterRegistry meterRegistry;
    private final KafkaProducerService kafkaProducerService;
    private final Counter userRegistrationSuccessCounter;
    private final Counter userRegistrationFailureCounter;
    private final Deque<UserDto> users = new ConcurrentLinkedDeque<>();
    private final AtomicInteger usersCount = new AtomicInteger(0);

    // Injetamos o MeterRegistry do Micrometer (geralmente fornecido pelo Spring Boot Actuator)
    public MicrometerService(MeterRegistry meterRegistry, KafkaProducerService kafkaProducerService) {
        this.meterRegistry = meterRegistry;
        this.kafkaProducerService = kafkaProducerService;

        // Criar ou registar os contadores com tags (metadados úteis para filtragem)
        this.userRegistrationSuccessCounter = Counter.builder("user.registrations.total")
                .description("Número total de registos de utilizadores bem-sucedidos")
                .tag("status", "success")
                .register(meterRegistry);

        this.userRegistrationFailureCounter = Counter.builder("user.registrations.total")
                .description("Número total de tentativas de registo falhadas")
                .tag("status", "failure")
                .register(meterRegistry);
    }

    public void registerUser(UserDto dto) {
        try {
            // Lógica de negócio para criar o utilizador...
            saveToDatabase(dto);

            // Publica o evento de utilizador registado no Kafka
            kafkaProducerService.publishUserRegisteredEvent(dto);

            // Incrementa o contador de sucesso
            userRegistrationSuccessCounter.increment();

        } catch (BusinessValidationException e) {
            // Incrementa o contador de falhas e propaga sem recriar stack trace
            userRegistrationFailureCounter.increment();
            throw e;
        } catch (Exception e) {
            userRegistrationFailureCounter.increment();
            throw e;
        }
    }

    private void saveToDatabase(UserDto dto) {
        if (dto == null || dto.name() == null || dto.name().isBlank() || dto.email() == null || dto.email().isBlank()) {
            throw new BusinessValidationException("Dados do utilizador inválidos: nome e email são obrigatórios");
        }
        users.addLast(dto);
        if (usersCount.incrementAndGet() > MAX_STORED_USERS && users.pollFirst() != null) {
            usersCount.decrementAndGet();
        }
    }

    public TaxResult calculateComplexTaxesAlternative() {
        Timer timer = meterRegistry.timer("business.tax.calculation.time", "region", "BR");

        // O timer mede automaticamente o tempo de execução do bloco Runnable ou Callable
        return timer.record(this::performHeavyCalculations);
    }

    @SneakyThrows
    private TaxResult performHeavyCalculations() {
        Random random = new Random();

        // Gera um valor aleatório entre 1000 e 5000 milissegundos (1 a 5 segundos)
        int min = 1000;
        int max = 5000;
        int tempoSleep = random.nextInt(max - min + 1) + min;

        Thread.sleep(tempoSleep);
        return new TaxResult(new BigDecimal(200));
    }
}

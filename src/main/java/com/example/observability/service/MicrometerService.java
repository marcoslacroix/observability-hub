package com.example.observability.service;

import com.example.observability.dto.TaxResult;
import com.example.observability.dto.UserDto;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.SneakyThrows;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class MicrometerService {

    private final MeterRegistry meterRegistry;
    private final Counter userRegistrationSuccessCounter;
    private final Counter userRegistrationFailureCounter;
    private final List<UserDto> users = new CopyOnWriteArrayList<>();

    // Injetamos o MeterRegistry do Micrometer (geralmente fornecido pelo Spring Boot Actuator)
    public MicrometerService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

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

            // Incrementa o contador de sucesso
            userRegistrationSuccessCounter.increment();

        } catch (Exception e) {
            // Incrementa o contador de falhas
            userRegistrationFailureCounter.increment();
            throw new RuntimeException("Erro ao registar utilizador", e);
        }
    }

    private void saveToDatabase(UserDto dto) {
        if (dto == null || dto.name() == null || dto.name().isBlank() || dto.email() == null || dto.email().isBlank()) {
            throw new IllegalArgumentException("Dados do utilizador inválidos: nome e email são obrigatórios");
        }
        users.add(dto);
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

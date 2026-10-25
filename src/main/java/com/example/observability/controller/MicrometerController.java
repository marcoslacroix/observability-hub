package com.example.observability.controller;

import java.util.Map;

import com.example.observability.dto.TaxResult;
import com.example.observability.dto.UserDto;
import com.example.observability.service.MicrometerService;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/micrometer")
@Log4j2
public class MicrometerController {

    private final MicrometerService micrometerService;

    public MicrometerController(MicrometerService micrometerService) {
        this.micrometerService = micrometerService;
    }

    @PostMapping("/register-user")
    public ResponseEntity<Map<String, String>> registerUser(@RequestBody UserDto dto) {
        log.info("Iniciando registo de utilizador: {}", dto);
        micrometerService.registerUser(dto);
        log.info("Utilizador registado com sucesso: {}", dto.email());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("status", "success", "message", "Utilizador registado com sucesso"));
    }

    @GetMapping("/heavy-calculations")
    public ResponseEntity<TaxResult> performHeavyCalculations() {
        log.info("Iniciando calculo complexo de impostos");
        TaxResult result = micrometerService.calculateComplexTaxesAlternative();
        log.info("Calculo concluido: {}", result);
        return ResponseEntity.ok(result);
    }

}

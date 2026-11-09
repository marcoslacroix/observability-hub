package com.example.observability.controller;

import com.example.observability.service.ThreadDumpService;
import com.example.observability.service.ThreadDumpService.ThreadDumpResult;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Endpoints para inspecao do monitor automatico de Thread Dump (gatilho em 90% de CPU),
 * captura manual sob demanda e simulacao controlada de estresse de CPU.
 */
@RestController
@RequestMapping("/threaddump")
@Log4j2
public class ThreadDumpController {

	private static final int MAX_STRESS_SECONDS = 60;

	private final ThreadDumpService threadDumpService;

	public ThreadDumpController(ThreadDumpService threadDumpService) {
		this.threadDumpService = threadDumpService;
	}

	/**
	 * Retorna o status atual do monitor de CPU, configuracao de threshold (90%) e lista de dumps gerados.
	 */
	@GetMapping("/status")
	public Map<String, Object> status() {
		return threadDumpService.getStatus();
	}

	/**
	 * Dispara manualmente a geracao de um Thread Dump completo em disco.
	 */
	@GetMapping("/capture")
	@PostMapping("/capture")
	public ThreadDumpResult captureManualDump() {
		log.info("Captura manual de Thread Dump solicitada via endpoint /threaddump/capture");
		return threadDumpService.captureThreadDump("manual", -1.0);
	}

	/**
	 * Simula carga intensa de CPU (> 90%) em todas as cores disponiveis por {@code seconds} segundos
	 * para validar o disparo automatico do Thread Dump pelo {@link ThreadDumpService}.
	 */
	@GetMapping("/stress-cpu")
	public Map<String, Object> stressCpu(@RequestParam(defaultValue = "15") int seconds) {
		if (seconds < 1 || seconds > MAX_STRESS_SECONDS) {
			throw new ResponseStatusException(
					HttpStatus.BAD_REQUEST,
					"seconds deve estar entre 1 e " + MAX_STRESS_SECONDS);
		}
		return threadDumpService.simulateCpuSpike(seconds);
	}
}

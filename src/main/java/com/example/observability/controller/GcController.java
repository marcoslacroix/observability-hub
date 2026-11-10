package com.example.observability.controller;

import com.example.observability.service.GcLogService;
import com.example.observability.service.GcLogService.GcSimulationResult;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Endpoints para inspecao do monitor de Garbage Collector (JMX NotificationListener)
 * e execucao de simulacoes de pressao de memoria com gravacao de relatorios em ./gclogs.
 */
@RestController
@RequestMapping("/gc")
@Log4j2
public class GcController {

	private static final int MAX_ROUNDS = 100;
	private static final int MAX_MB_PER_ROUND = 50;
	private static final int MAX_RETAIN_PERCENT = 50;

	private final GcLogService gcLogService;

	public GcController(GcLogService gcLogService) {
		this.gcLogService = gcLogService;
	}

	/**
	 * Retorna o status atual do monitor de GC, coletores da JVM, ultimos eventos capturados
	 * e lista de arquivos de log gerados em ./gclogs.
	 */
	@GetMapping("/status")
	public Map<String, Object> status() {
		return gcLogService.getStatus();
	}

	/**
	 * Executa uma simulacao controlada de pressao no Garbage Collector, capturando todas as coletas
	 * (Minor GC e Major GC) ocorridas durante a janela de teste e salvando um relatorio detalhado em ./gclogs.
	 */
	@RequestMapping(value = "/simulate", method = {RequestMethod.GET, RequestMethod.POST})
	public GcSimulationResult simulate(
			@RequestParam(defaultValue = "15") int rounds,
			@RequestParam(defaultValue = "20") int mbPerRound,
			@RequestParam(defaultValue = "0") int retainPercent,
			@RequestParam(defaultValue = "false") boolean triggerExplicitGc) {
		if (rounds < 1 || rounds > MAX_ROUNDS) {
			throw new ResponseStatusException(
					HttpStatus.BAD_REQUEST,
					"rounds deve estar entre 1 e " + MAX_ROUNDS);
		}
		if (mbPerRound < 1 || mbPerRound > MAX_MB_PER_ROUND) {
			throw new ResponseStatusException(
					HttpStatus.BAD_REQUEST,
					"mbPerRound deve estar entre 1 e " + MAX_MB_PER_ROUND);
		}
		if (retainPercent < 0 || retainPercent > MAX_RETAIN_PERCENT) {
			throw new ResponseStatusException(
					HttpStatus.BAD_REQUEST,
					"retainPercent deve estar entre 0 e " + MAX_RETAIN_PERCENT);
		}
		try {
			return gcLogService.runSimulation(rounds, mbPerRound, retainPercent, triggerExplicitGc);
		} catch (IllegalStateException e) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
		}
	}
}

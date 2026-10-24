package com.example.pyroscope.pyroscope;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import lombok.extern.log4j.Log4j;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Endpoints de teste que geram alocação de memória para o profiling do Pyroscope.
 */
@RestController
@RequestMapping("/alloc")
@Log4j2
public class AllocationController {

	private static final int MAX_MB = 100;
	private static final int MAX_ITEMS = 1_000_000;

	/**
	 * Aloca blocos de 1 MB de byte[] até somar o total pedido.
	 */
	@GetMapping("/bytes")
	public Map<String, Object> allocateBytes(@RequestParam(defaultValue = "10") int mb) {
		log.info("Iniciando alocacao de {} MB em blocos de byte[]", mb);
		validate(mb, MAX_MB, "mb");
		List<byte[]> blocks = new ArrayList<>(mb);
		long checksum = 0;
		for (int i = 0; i < mb; i++) {
			byte[] block = new byte[1024 * 1024];
			ThreadLocalRandom.current().nextBytes(block);
			checksum += block[0];
			blocks.add(block);
		}
		log.info("Alocacao concluida: {} MB alocados (checksum={})", blocks.size(), checksum);
		return Map.of("allocatedMb", blocks.size(), "checksum", checksum);
	}

	/**
	 * Cria muitos objetos pequenos (Strings em um HashMap), gerando pressão no GC.
	 */
	@GetMapping("/objects")
	public Map<String, Object> allocateObjects(@RequestParam(defaultValue = "200000") int items) {
		log.info("Iniciando criacao de {} objetos no HashMap", items);
        validate(items, MAX_ITEMS, "items");
		Map<String, String> map = new HashMap<>();
		for (int i = 0; i < items; i++) {
			map.put("key-" + i, "value-" + i + "-" + System.nanoTime());
		}
		log.info("Criacao de objetos concluida: {} itens inseridos", map.size());
		return Map.of("items", map.size());
	}

	private static void validate(int value, int max, String name) {
		if (value < 1 || value > max) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					name + " deve estar entre 1 e " + max);
		}
	}

}

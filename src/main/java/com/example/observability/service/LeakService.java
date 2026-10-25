package com.example.observability.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

/**
 * Memory leak PROPOSITAL (leve) para demonstrar o Flame Graph de alocacao do Pyroscope.
 * NAO usar em producao: a lista abaixo nunca e esvaziada (exceto via {@link #clear()}).
 */
@Service
@Log4j2
public class LeakService {

	/** Cache "esquecido": cresce a cada chamada e nunca e limpo -> leak. */
	private static final List<LeakedEntry> LEAKED_CACHE = new ArrayList<>();

	private static final AtomicLong SEQUENCE = new AtomicLong();

	/** Objeto retido: payload de ~10 KB por entrada. */
	record LeakedEntry(long id, String label, byte[] payload) {
	}

	/**
	 * Cria {@code entries} objetos e os retem para sempre em {@link #LEAKED_CACHE}.
	 */
	public long leak(int entries) {
		for (int i = 0; i < entries; i++) {
			LEAKED_CACHE.add(buildEntry()); // <- linha do leak: retem a referencia
		}
		log.warn("Leak: {} entradas retidas (total={})", entries, LEAKED_CACHE.size());
		return LEAKED_CACHE.size();
	}

	private LeakedEntry buildEntry() {
		long id = SEQUENCE.incrementAndGet();
		byte[] payload = new byte[10 * 1024]; // <- alocacao dominante no flame graph
		return new LeakedEntry(id, "leaked-entry-" + id, payload);
	}

	public int size() {
		return LEAKED_CACHE.size();
	}

	/** Libera o leak (para os testes nao derrubarem a JVM). */
	public int clear() {
		int removed = LEAKED_CACHE.size();
		LEAKED_CACHE.clear();
		return removed;
	}

}

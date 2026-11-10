package com.example.observability.service;

import com.example.observability.service.GcLogService.GcEventRecord;
import com.example.observability.service.GcLogService.GcSimulationResult;
import com.example.observability.service.GcLogService.PoolTransition;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GcLogServiceTest {

	@TempDir
	Path tempDir;

	@Test
	@DisplayName("Deve registrar eventos de GC nas metricas Micrometer e no historico em memoria")
	void shouldRecordGcEventAndIncrementMetrics() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		GcLogService service = new GcLogService(registry, true, tempDir);

		GcEventRecord sampleEvent = new GcEventRecord(
				10L,
				Instant.now().toString(),
				"G1 Young Generation",
				"end of minor GC",
				"G1 Evacuation Pause",
				8L,
				250L * 1024 * 1024,
				40L * 1024 * 1024,
				210L * 1024 * 1024,
				Map.of(
						"G1 Eden Space", new PoolTransition(210, 0),
						"G1 Survivor Space", new PoolTransition(15, 12),
						"G1 Old Gen", new PoolTransition(25, 28)));

		service.recordGcEvent(sampleEvent);

		double eventCount = registry.get("jvm.gc.simulation.events.total")
				.tag("collector", "G1 Young Generation")
				.tag("action", "end of minor GC")
				.tag("cause", "G1 Evacuation Pause")
				.counter()
				.count();
		assertEquals(1.0, eventCount);

		double reclaimedBytes = registry.get("jvm.gc.simulation.reclaimed.bytes.total")
				.tag("collector", "G1 Young Generation")
				.counter()
				.count();
		assertEquals(210.0 * 1024 * 1024, reclaimedBytes);

		Map<String, Object> status = service.getStatus();
		assertEquals(1L, status.get("totalGcEventsCaptured"));
	}

	@Test
	@DisplayName("Deve executar simulacao de GC, capturar eventos JMX e gravar relatorio exclusivo em disco")
	void shouldRunSimulationAndWriteReportFile() throws IOException {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		GcLogService service = new GcLogService(registry, true, tempDir);
		service.registerGcListeners();

		try {
			GcSimulationResult r1 = service.runSimulation(3, 5, 0, true);
			GcSimulationResult r2 = service.runSimulation(3, 5, 20, true);

			assertEquals(15L, r1.totalAllocatedMb());
			assertEquals(15L, r2.totalAllocatedMb());
			assertNotEquals(r1.logFilePath(), r2.logFilePath(), "Cada simulacao deve gerar um arquivo exclusivo");

			Path file1 = Path.of(r1.logFilePath());
			Path file2 = Path.of(r2.logFilePath());
			assertTrue(Files.exists(file1), "Relatorio da 1a simulacao deve existir no disco");
			assertTrue(Files.exists(file2), "Relatorio da 2a simulacao deve existir no disco");

			String content1 = Files.readString(file1);
			assertTrue(content1.contains("OBSERVABILITY HUB - RELATORIO DE SIMULACAO DO GARBAGE COLLECTOR"));
			assertTrue(content1.contains("1. LINHA DO TEMPO DE EVENTOS DO GARBAGE COLLECTOR CAPTURADOS"));
			assertTrue(content1.contains("2. RESUMO ESTATISTICO E DIAGNOSTICO DE COMPORTAMENTO DO GC"));
			assertTrue(content1.contains("retainPercent = 0%"));

			String content2 = Files.readString(file2);
			assertTrue(content2.contains("retainPercent > 0%"));

			List<String> files = service.listGcLogFiles();
			assertEquals(2, files.size(), "Ambos os arquivos de log devem estar listados");
		} finally {
			service.unregisterGcListeners();
		}
	}
}

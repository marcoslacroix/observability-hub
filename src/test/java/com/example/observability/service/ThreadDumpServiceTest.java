package com.example.observability.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadDumpServiceTest {

	@TempDir
	Path tempDir;

	@Test
	@DisplayName("Nao deve gerar thread dump quando o uso de CPU estiver abaixo de 90%")
	void shouldNotDumpWhenCpuBelowThreshold() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		AtomicReference<Double> simulatedCpu = new AtomicReference<>(0.85); // 85% < 90%

		ThreadDumpService service = new ThreadDumpService(
				registry, true, 0.90, 60_000L, tempDir, simulatedCpu::get);

		service.monitorCpuAndDumpIfNeeded();

		assertTrue(service.listThreadDumpFiles().isEmpty(), "Nenhum arquivo .tdump deveria ser gerado abaixo de 90%");
	}

	@Test
	@DisplayName("Deve gerar thread dump automaticamente quando o uso de CPU atingir 90% ou mais e respeitar cooldown")
	void shouldCaptureThreadDumpAutomaticallyWhenCpuReaches90Percent() throws IOException {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		AtomicReference<Double> simulatedCpu = new AtomicReference<>(0.92); // 92% >= 90%

		ThreadDumpService service = new ThreadDumpService(
				registry, true, 0.90, 60_000L, tempDir, simulatedCpu::get);

		// 1a execucao: CPU em 92% -> deve gerar o dump
		service.monitorCpuAndDumpIfNeeded();

		List<String> files = service.listThreadDumpFiles();
		assertEquals(1, files.size(), "Deve ter gerado exatamente 1 thread dump ao atingir >= 90% de CPU");

		Path generatedFile = tempDir.resolve(files.getFirst());
		assertTrue(Files.exists(generatedFile));
		String content = Files.readString(generatedFile);
		assertTrue(content.contains("OBSERVABILITY HUB - DIAGNOSTICO DE THREAD DUMP DA JVM"));
		assertTrue(content.contains("Gatilho (Trigger)    : cpu_threshold"));
		assertTrue(content.contains("92.00% (Limiar: 90.00%)"));
		assertTrue(content.contains("1. DETECCAO DE DEADLOCKS"));
		assertTrue(content.contains("2. TOP THREADS POR TEMPO ACUMULADO DE CPU"));
		assertTrue(content.contains("3. STACK TRACES COMPLETOS"));

		// Verifica a metrica Micrometer
		double count = registry.get("jvm.thread.dumps.total")
				.tag("trigger", "cpu_threshold")
				.tag("status", "success")
				.counter()
				.count();
		assertEquals(1.0, count);

		// 2a execucao imediata: CPU continua em 95%, mas esta em cooldown (60s) -> nao deve gerar outro
		simulatedCpu.set(0.95);
		service.monitorCpuAndDumpIfNeeded();
		assertEquals(1, service.listThreadDumpFiles().size(), "Nao deve gerar novo dump durante a janela de cooldown");
	}

	@Test
	@DisplayName("Sempre deve criar um novo arquivo exclusivo e jamais sobrescrever ou apagar os anteriores")
	void shouldAlwaysCreateNewUniqueFileAndNeverOverwritePrevious() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		ThreadDumpService service = new ThreadDumpService(
				registry, true, 0.90, 0L, tempDir, () -> 0.95);

		ThreadDumpService.ThreadDumpResult r1 = service.captureThreadDump("cpu_threshold", 0.91);
		ThreadDumpService.ThreadDumpResult r2 = service.captureThreadDump("cpu_threshold", 0.91);
		ThreadDumpService.ThreadDumpResult r3 = service.captureThreadDump("cpu_threshold", 0.91);

		assertNotEquals(r1.filePath(), r2.filePath(), "O segundo dump deve ter caminho distinto do primeiro");
		assertNotEquals(r2.filePath(), r3.filePath(), "O terceiro dump deve ter caminho distinto do segundo");
		assertNotEquals(r1.filePath(), r3.filePath(), "O terceiro dump deve ter caminho distinto do primeiro");

		assertTrue(Files.exists(Path.of(r1.filePath())), "O primeiro arquivo deve continuar existindo intacto");
		assertTrue(Files.exists(Path.of(r2.filePath())), "O segundo arquivo deve continuar existindo intacto");
		assertTrue(Files.exists(Path.of(r3.filePath())), "O terceiro arquivo deve continuar existindo intacto");

		List<String> allFiles = service.listThreadDumpFiles();
		assertEquals(3, allFiles.size(), "Todos os 3 arquivos criados devem permanecer salvos");
		Set<String> uniqueNames = new HashSet<>(allFiles);
		assertEquals(3, uniqueNames.size(), "Todos os 3 arquivos devem ter nomes unicos");
	}
}

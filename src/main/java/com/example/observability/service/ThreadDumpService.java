package com.example.observability.service;

import com.sun.management.HotSpotDiagnosticMXBean;
import com.sun.management.OperatingSystemMXBean;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Servico responsavel por monitorar continuamente o uso de CPU do processo JVM
 * e gerar Thread Dumps automaticamente quando o consumo atingir ou ultrapassar
 * o limiar configurado (padrao: 90%).
 */
@Service
@Log4j2
public class ThreadDumpService {

	private static final DateTimeFormatter FILE_TS_FORMATTER = DateTimeFormatter
			.ofPattern("yyyyMMdd-HHmmss-SSS")
			.withZone(ZoneId.systemDefault());

	private final MeterRegistry meterRegistry;
	private final boolean enabled;
	private final double cpuThreshold;
	private final long cooldownMs;
	private final Path outputDir;
	private final DoubleSupplier cpuLoadSupplier;

	private final ThreadMXBean threadMXBean;
	private final OperatingSystemMXBean osMXBean;
	private final HotSpotDiagnosticMXBean hotSpotDiagnosticMXBean;

	private final AtomicLong dumpSequence = new AtomicLong(0);
	private final AtomicLong lastDumpEpochMs = new AtomicLong(0);
	private final AtomicReference<Double> lastObservedCpuLoad = new AtomicReference<>(0.0);
	private final AtomicReference<String> lastDumpFilePath = new AtomicReference<>(null);
	private final AtomicBoolean cpuStressRunning = new AtomicBoolean(false);

	@Autowired
	public ThreadDumpService(
			MeterRegistry meterRegistry,
			@Value("${app.threaddump.enabled:true}") boolean enabled,
			@Value("${app.threaddump.cpu-threshold:0.90}") double cpuThreshold,
			@Value("${app.threaddump.cooldown-ms:60000}") long cooldownMs,
			@Value("${app.threaddump.output-dir:./threaddumps}") String outputDir) {
		this(meterRegistry, enabled, cpuThreshold, cooldownMs, Paths.get(outputDir), null);
	}

	ThreadDumpService(
			MeterRegistry meterRegistry,
			boolean enabled,
			double cpuThreshold,
			long cooldownMs,
			Path outputDir,
			DoubleSupplier customCpuLoadSupplier) {
		this.meterRegistry = meterRegistry;
		this.enabled = enabled;
		this.cpuThreshold = cpuThreshold;
		this.cooldownMs = cooldownMs;
		this.outputDir = outputDir;

		this.threadMXBean = ManagementFactory.getThreadMXBean();
		if (this.threadMXBean.isThreadCpuTimeSupported() && !this.threadMXBean.isThreadCpuTimeEnabled()) {
			this.threadMXBean.setThreadCpuTimeEnabled(true);
		}
		this.osMXBean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
		this.hotSpotDiagnosticMXBean = resolveHotSpotDiagnosticMXBean();

		this.cpuLoadSupplier = customCpuLoadSupplier != null
				? customCpuLoadSupplier
				: this::readJvmProcessCpuLoad;

		Gauge.builder("jvm.threaddump.cpu.threshold.ratio", () -> this.cpuThreshold)
				.description("Limiar de uso de CPU (0.0 a 1.0) configurado para disparo automatico de thread dump")
				.register(meterRegistry);

		Gauge.builder("jvm.threaddump.last.cpu.ratio", lastObservedCpuLoad::get)
				.description("Ultimo uso de CPU do processo observado pelo monitor de thread dump (0.0 a 1.0)")
				.register(meterRegistry);
	}

	public record TopThreadCpu(long threadId, String threadName, Thread.State state, long cpuTimeMs) {
	}

	public record ThreadDumpResult(
			String filePath,
			String trigger,
			Instant timestamp,
			double processCpuPercent,
			double thresholdPercent,
			int totalThreads,
			int deadlockedThreadsCount,
			List<TopThreadCpu> topCpuThreads) {
	}

	/**
	 * Job agendado que verifica periodicamente o uso de CPU da aplicacao.
	 * Se o uso for >= cpuThreshold (ex.: 0.90 = 90%) e o tempo de cooldown tiver passado,
	 * gera um thread dump completo em disco.
	 */
	@Scheduled(fixedDelayString = "${app.threaddump.check-interval-ms:5000}")
	public void monitorCpuAndDumpIfNeeded() {
		if (!enabled) {
			return;
		}

		double cpuLoad = cpuLoadSupplier.getAsDouble();
		if (Double.isNaN(cpuLoad) || cpuLoad < 0.0) {
			return;
		}

		lastObservedCpuLoad.set(cpuLoad);

		if (cpuLoad >= cpuThreshold) {
			long now = System.currentTimeMillis();
			long previous = lastDumpEpochMs.get();
			if (now - previous < cooldownMs) {
				log.debug("Uso de CPU em {}% (>= {}%), mas em cooldown ({} ms restantes)",
						formatPct(cpuLoad), formatPct(cpuThreshold), cooldownMs - (now - previous));
				return;
			}

			if (lastDumpEpochMs.compareAndSet(previous, now)) {
				log.warn("ALERTA DE CPU ALTA: Uso de CPU da JVM atingiu {}% (limiar: {}%). Iniciando captura automatica de Thread Dump...",
						formatPct(cpuLoad), formatPct(cpuThreshold));
				captureThreadDump("cpu_threshold", cpuLoad);
			}
		}
	}

	/**
	 * Gera um Thread Dump imediatamente (seja por gatilho automatico de CPU ou chamada manual),
	 * criando sempre um novo arquivo exclusivo sem jamais sobrescrever dumps anteriores.
	 */
	public synchronized ThreadDumpResult captureThreadDump(String trigger, double currentCpuLoad) {
		Instant now = Instant.now();
		double effectiveCpu = (Double.isNaN(currentCpuLoad) || currentCpuLoad < 0.0)
				? Math.max(0.0, cpuLoadSupplier.getAsDouble())
				: currentCpuLoad;

		int cpuPctInt = (int) Math.round(effectiveCpu * 100);
		Path targetFile = null;

		try {
			Files.createDirectories(outputDir);
			targetFile = resolveUniqueDumpFile(now, cpuPctInt);

			ThreadInfo[] allThreads = threadMXBean.dumpAllThreads(true, true);
			long[] deadlockedIds = threadMXBean.findDeadlockedThreads();
			int deadlockedCount = deadlockedIds != null ? deadlockedIds.length : 0;
			List<TopThreadCpu> topCpuThreads = extractTopCpuThreads(allThreads, 10);

			String dumpContent = buildThreadDumpText(now, trigger, effectiveCpu, allThreads, deadlockedIds, topCpuThreads);
			// Usa CREATE_NEW para garantir em nivel de sistema de arquivos que nenhum arquivo existente seja sobrescrito
			Files.writeString(targetFile, dumpContent, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

			appendHotSpotVirtualThreadsDump(targetFile);

			String absolutePath = targetFile.toAbsolutePath().toString();
			lastDumpFilePath.set(absolutePath);
			incrementCounter(trigger, "success");

			log.warn("Thread Dump gerado com sucesso [gatilho={}, cpu={}%, threads={}, deadlocks={}, arquivo={}]",
					trigger, formatPct(effectiveCpu), allThreads.length, deadlockedCount, absolutePath);

			return new ThreadDumpResult(
					absolutePath,
					trigger,
					now,
					roundTwoDecimals(effectiveCpu * 100),
					roundTwoDecimals(cpuThreshold * 100),
					allThreads.length,
					deadlockedCount,
					topCpuThreads);
		} catch (Exception e) {
			incrementCounter(trigger, "failure");
			log.error("Falha ao gerar Thread Dump em {}: {}", targetFile, e.getMessage(), e);
			throw new IllegalStateException("Erro ao gerar Thread Dump", e);
		}
	}

	/**
	 * Resolve um caminho de arquivo garantidamente inedito no diretorio de saida,
	 * combinando timestamp com milissegundos, sequencial atomico e verificacao de colisao.
	 */
	private Path resolveUniqueDumpFile(Instant now, int cpuPctInt) {
		String timestampPart = FILE_TS_FORMATTER.format(now);
		long seq = dumpSequence.incrementAndGet();
		Path candidate = outputDir.resolve(
				String.format("threaddump-%s-%d-cpu%dpct.tdump", timestampPart, seq, cpuPctInt));
		int collisionAttempt = 1;
		while (Files.exists(candidate)) {
			candidate = outputDir.resolve(
					String.format("threaddump-%s-%d-%d-cpu%dpct.tdump", timestampPart, seq, collisionAttempt++, cpuPctInt));
		}
		return candidate;
	}

	/**
	 * Dispara estresse controlado de CPU em todas as cores disponiveis para validar
	 * o gatilho automatico de 90% de uso de CPU.
	 */
	public Map<String, Object> simulateCpuSpike(int seconds) {
		int cores = Runtime.getRuntime().availableProcessors();
		if (!cpuStressRunning.compareAndSet(false, true)) {
			return Map.of(
					"status", "already_running",
					"message", "Ja existe uma simulacao de estresse de CPU em andamento",
					"cores", cores);
		}

		long durationMs = seconds * 1000L;
		long deadline = System.currentTimeMillis() + durationMs;

		log.warn("Iniciando simulacao de pico de CPU (>90%) em {} nucleos por {} segundos...", cores, seconds);

		for (int i = 0; i < cores; i++) {
			final int workerId = i + 1;
			Thread worker = new Thread(() -> {
				double acc = 0;
				while (System.currentTimeMillis() < deadline) {
					for (int j = 0; j < 10_000; j++) {
						acc += Math.sin(j) * Math.cos(j);
					}
				}
				if (acc == Double.MIN_VALUE) {
					log.debug("Worker {} finalizou: {}", workerId, acc);
				}
			}, "cpu-stress-worker-" + workerId);
			worker.setDaemon(true);
			worker.setPriority(Thread.MAX_PRIORITY);
			worker.start();
		}

		CompletableFuture.runAsync(() -> {
			try {
				Thread.sleep(durationMs);
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			} finally {
				cpuStressRunning.set(false);
				log.info("Simulacao de pico de CPU concluida apos {} segundos", seconds);
			}
		});

		return Map.of(
				"status", "started",
				"seconds", seconds,
				"workersSpawned", cores,
				"thresholdPercent", roundTwoDecimals(cpuThreshold * 100));
	}

	public Map<String, Object> getStatus() {
		double currentCpu = Math.max(0.0, cpuLoadSupplier.getAsDouble());
		List<String> files = listThreadDumpFiles();
		return Map.of(
				"enabled", enabled,
				"currentProcessCpuPercent", roundTwoDecimals(currentCpu * 100),
				"thresholdPercent", roundTwoDecimals(cpuThreshold * 100),
				"cooldownMs", cooldownMs,
				"outputDir", outputDir.toAbsolutePath().toString(),
				"lastDumpFile", lastDumpFilePath.get() != null ? lastDumpFilePath.get() : "nenhum",
				"lastDumpEpochMs", lastDumpEpochMs.get(),
				"savedDumpsCount", files.size(),
				"savedDumps", files);
	}

	public List<String> listThreadDumpFiles() {
		if (!Files.exists(outputDir)) {
			return List.of();
		}
		try (Stream<Path> stream = Files.list(outputDir)) {
			return stream
					.filter(p -> p.getFileName().toString().endsWith(".tdump"))
					.sorted(Comparator.comparingLong(this::getLastModifiedMillis).reversed())
					.map(p -> p.getFileName().toString())
					.toList();
		} catch (IOException e) {
			log.warn("Nao foi possivel listar os arquivos de thread dump em {}: {}", outputDir, e.getMessage());
			return List.of();
		}
	}

	private double readJvmProcessCpuLoad() {
		if (osMXBean == null) {
			return -1.0;
		}
		return osMXBean.getProcessCpuLoad();
	}

	private List<TopThreadCpu> extractTopCpuThreads(ThreadInfo[] allThreads, int limit) {
		if (!threadMXBean.isThreadCpuTimeSupported() || !threadMXBean.isThreadCpuTimeEnabled()) {
			return List.of();
		}
		return Arrays.stream(allThreads)
				.map(info -> {
					long cpuNanos = threadMXBean.getThreadCpuTime(info.getThreadId());
					long cpuMs = cpuNanos > 0 ? cpuNanos / 1_000_000 : 0;
					return new TopThreadCpu(info.getThreadId(), info.getThreadName(), info.getThreadState(), cpuMs);
				})
				.sorted(Comparator.comparingLong(TopThreadCpu::cpuTimeMs).reversed())
				.limit(limit)
				.toList();
	}

	private String buildThreadDumpText(
			Instant timestamp,
			String trigger,
			double processCpuLoad,
			ThreadInfo[] allThreads,
			long[] deadlockedIds,
			List<TopThreadCpu> topCpuThreads) {

		StringBuilder sb = new StringBuilder(32_768);
		double systemCpuLoad = osMXBean != null ? Math.max(0.0, osMXBean.getCpuLoad()) : 0.0;

		sb.append("================================================================================\n");
		sb.append("OBSERVABILITY HUB - DIAGNOSTICO DE THREAD DUMP DA JVM\n");
		sb.append("================================================================================\n");
		sb.append("Timestamp            : ").append(timestamp).append('\n');
		sb.append("Gatilho (Trigger)    : ").append(trigger).append('\n');
		sb.append("CPU do Processo JVM  : ").append(formatPct(processCpuLoad)).append("% (Limiar: ")
				.append(formatPct(cpuThreshold)).append("%)\n");
		sb.append("CPU Global Sistema   : ").append(formatPct(systemCpuLoad)).append("%\n");
		sb.append("Nucleos Disponiveis  : ").append(Runtime.getRuntime().availableProcessors()).append('\n');
		sb.append("JVM                  : ").append(System.getProperty("java.vm.name")).append(" (")
				.append(System.getProperty("java.runtime.version")).append(")\n");
		sb.append("Threads Ativas       : ").append(threadMXBean.getThreadCount())
				.append(" (Pico: ").append(threadMXBean.getPeakThreadCount())
				.append(", Daemon: ").append(threadMXBean.getDaemonThreadCount())
				.append(", Total Iniciadas: ").append(threadMXBean.getTotalStartedThreadCount()).append(")\n");

		Map<Thread.State, Long> stateSummary = Arrays.stream(allThreads)
				.collect(Collectors.groupingBy(ThreadInfo::getThreadState, Collectors.counting()));
		sb.append("Resumo por Estado    : ").append(stateSummary).append("\n\n");

		// Secao 1: Deadlocks
		sb.append("--------------------------------------------------------------------------------\n");
		sb.append("1. DETECCAO DE DEADLOCKS\n");
		sb.append("--------------------------------------------------------------------------------\n");
		if (deadlockedIds == null || deadlockedIds.length == 0) {
			sb.append("Nenhum deadlock detectado.\n\n");
		} else {
			sb.append("ATENCAO! ").append(deadlockedIds.length).append(" threads em DEADLOCK detectadas:\n");
			ThreadInfo[] deadlockedInfos = threadMXBean.getThreadInfo(deadlockedIds, true, true);
			for (ThreadInfo info : deadlockedInfos) {
				if (info != null) {
					formatThreadInfo(sb, info);
				}
			}
			sb.append('\n');
		}

		// Secao 2: Top Threads por Consumo de CPU
		sb.append("--------------------------------------------------------------------------------\n");
		sb.append("2. TOP THREADS POR TEMPO ACUMULADO DE CPU\n");
		sb.append("--------------------------------------------------------------------------------\n");
		for (TopThreadCpu top : topCpuThreads) {
			sb.append(String.format(Locale.US, "  - [id=%-4d] %-45s | Estado: %-13s | CPU Total: %d ms%n",
					top.threadId(), top.threadName(), top.state(), top.cpuTimeMs()));
		}
		sb.append('\n');

		// Secao 3: Stack Traces completos de todas as Platform Threads
		sb.append("--------------------------------------------------------------------------------\n");
		sb.append("3. STACK TRACES COMPLETOS (PLATFORM THREADS & LOCKS)\n");
		sb.append("--------------------------------------------------------------------------------\n\n");
		for (ThreadInfo info : allThreads) {
			formatThreadInfo(sb, info);
		}

		return sb.toString();
	}

	private void formatThreadInfo(StringBuilder sb, ThreadInfo info) {
		sb.append('"').append(info.getThreadName()).append('"')
				.append(" Id=").append(info.getThreadId())
				.append(" prio=").append(info.getPriority())
				.append(info.isDaemon() ? " daemon" : "")
				.append(" ").append(info.getThreadState());
		if (info.getLockName() != null) {
			sb.append(" on ").append(info.getLockName());
		}
		if (info.getLockOwnerName() != null) {
			sb.append(" owned by \"").append(info.getLockOwnerName())
					.append("\" Id=").append(info.getLockOwnerId());
		}
		if (info.isSuspended()) {
			sb.append(" (suspended)");
		}
		if (info.isInNative()) {
			sb.append(" (in native)");
		}
		sb.append('\n');

		StackTraceElement[] stackTrace = info.getStackTrace();
		MonitorInfo[] monitors = info.getLockedMonitors();
		for (int i = 0; i < stackTrace.length; i++) {
			StackTraceElement ste = stackTrace[i];
			sb.append("\tat ").append(ste.toString()).append('\n');
			if (i == 0 && info.getLockInfo() != null) {
				switch (info.getThreadState()) {
					case BLOCKED -> sb.append("\t-  blocked on ").append(info.getLockInfo()).append('\n');
					case WAITING, TIMED_WAITING -> sb.append("\t-  waiting on ").append(info.getLockInfo()).append('\n');
					default -> {
					}
				}
			}
			for (MonitorInfo mi : monitors) {
				if (mi.getLockedStackDepth() == i) {
					sb.append("\t-  locked ").append(mi).append('\n');
				}
			}
		}

		LockInfo[] synchronizers = info.getLockedSynchronizers();
		if (synchronizers.length > 0) {
			sb.append("\n\tNumber of locked synchronizers = ").append(synchronizers.length).append('\n');
			for (LockInfo li : synchronizers) {
				sb.append("\t- ").append(li).append('\n');
			}
		}
		sb.append('\n');
	}

	/**
	 * No Java 21+, HotSpotDiagnosticMXBean.dumpThreads tambem exporta Virtual Threads
	 * e seus carrier threads. Anexamos essa visao ao final do arquivo .tdump.
	 */
	private void appendHotSpotVirtualThreadsDump(Path targetFile) {
		if (hotSpotDiagnosticMXBean == null) {
			return;
		}
		Path tempHotSpotDump = outputDir.resolve(targetFile.getFileName().toString() + ".hotspot.tmp");
		try {
			Files.deleteIfExists(tempHotSpotDump);
			hotSpotDiagnosticMXBean.dumpThreads(
					tempHotSpotDump.toAbsolutePath().toString(),
					HotSpotDiagnosticMXBean.ThreadDumpFormat.TEXT_PLAIN);
			if (Files.exists(tempHotSpotDump)) {
				String hotspotText = Files.readString(tempHotSpotDump, StandardCharsets.UTF_8);
				String sectionHeader = "\n--------------------------------------------------------------------------------\n"
						+ "4. HOTSPOT DIAGNOSTIC DUMP (INCLUI VIRTUAL THREADS - JAVA 25)\n"
						+ "--------------------------------------------------------------------------------\n";
				Files.writeString(targetFile, sectionHeader + hotspotText, StandardCharsets.UTF_8,
						StandardOpenOption.APPEND);
			}
		} catch (Exception e) {
			log.debug("Dump complementar do HotSpotDiagnosticMXBean ignorado: {}", e.getMessage());
		} finally {
			try {
				Files.deleteIfExists(tempHotSpotDump);
			} catch (IOException ignored) {
			}
		}
	}

	private long getLastModifiedMillis(Path path) {
		try {
			return Files.getLastModifiedTime(path).toMillis();
		} catch (IOException e) {
			return 0L;
		}
	}

	private HotSpotDiagnosticMXBean resolveHotSpotDiagnosticMXBean() {
		try {
			return ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
		} catch (Exception e) {
			return null;
		}
	}

	private void incrementCounter(String trigger, String status) {
		Counter.builder("jvm.thread.dumps.total")
				.description("Total de thread dumps gerados pela aplicacao")
				.tag("trigger", trigger)
				.tag("status", status)
				.register(meterRegistry)
				.increment();
	}

	private static String formatPct(double ratio) {
		return String.format(Locale.US, "%.2f", ratio * 100.0);
	}

	private static double roundTwoDecimals(double value) {
		return Math.round(value * 100.0) / 100.0;
	}
}

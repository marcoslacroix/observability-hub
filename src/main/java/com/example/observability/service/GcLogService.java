package com.example.observability.service;

import com.sun.management.GarbageCollectionNotificationInfo;
import com.sun.management.GcInfo;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.management.ListenerNotFoundException;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Servico responsavel por capturar notificacoes JMX do Garbage Collector em tempo real,
 * emitir logs estruturados para o Grafana Loki, registrar metricas no Micrometer/Prometheus
 * e gravar relatorios detalhados de simulacao de GC em disco (./gclogs).
 */
@Service
@Log4j2
public class GcLogService {

	private static final int MAX_RECENT_EVENTS = 100;
	private static final long BYTES_PER_MB = 1024L * 1024L;

	private static final DateTimeFormatter FILE_TS_FORMATTER = DateTimeFormatter
			.ofPattern("yyyyMMdd-HHmmss-SSS")
			.withZone(ZoneId.systemDefault());

	private static final DateTimeFormatter LOG_TIME_FORMATTER = DateTimeFormatter
			.ofPattern("HH:mm:ss.SSS")
			.withZone(ZoneId.systemDefault());

	private final MeterRegistry meterRegistry;
	private final boolean enabled;
	private final Path outputDir;
	private final MemoryMXBean memoryMXBean;
	private final Set<String> heapPoolNames;

	private final Deque<GcEventRecord> recentEvents = new ConcurrentLinkedDeque<>();
	private final List<GcEventRecord> activeSimulationEvents = new CopyOnWriteArrayList<>();
	private final List<RegisteredListener> registeredListeners = new ArrayList<>();

	private final AtomicLong simulationSequence = new AtomicLong(0);
	private final AtomicLong totalGcEventsCaptured = new AtomicLong(0);
	private final AtomicBoolean simulationRunning = new AtomicBoolean(false);
	private final AtomicReference<String> lastSimulationFilePath = new AtomicReference<>(null);

	@Autowired
	public GcLogService(
			MeterRegistry meterRegistry,
			@Value("${app.gclog.enabled:true}") boolean enabled,
			@Value("${app.gclog.output-dir:./gclogs}") String outputDir) {
		this(meterRegistry, enabled, Paths.get(outputDir));
	}

	public GcLogService(MeterRegistry meterRegistry, boolean enabled, Path outputDir) {
		this.meterRegistry = meterRegistry;
		this.enabled = enabled;
		this.outputDir = outputDir;
		this.memoryMXBean = ManagementFactory.getMemoryMXBean();
		this.heapPoolNames = ManagementFactory.getMemoryPoolMXBeans().stream()
				.filter(pool -> pool.getType() == MemoryType.HEAP)
				.map(MemoryPoolMXBean::getName)
				.collect(Collectors.toSet());
	}

	@PostConstruct
	public void registerGcListeners() {
		if (!enabled) {
			log.info("Monitor de logs de Garbage Collector desativado (app.gclog.enabled=false)");
			return;
		}
		for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
			if (gcBean instanceof NotificationEmitter emitter) {
				NotificationListener listener = this::handleGcNotification;
				emitter.addNotificationListener(listener, null, null);
				registeredListeners.add(new RegisteredListener(emitter, listener));
				log.info("Listener JMX de Garbage Collector registrado para o coletor: {}", gcBean.getName());
			}
		}
	}

	@PreDestroy
	public void unregisterGcListeners() {
		for (RegisteredListener reg : registeredListeners) {
			try {
				reg.emitter().removeNotificationListener(reg.listener());
			} catch (ListenerNotFoundException ignored) {
				// Listener ja removido no encerramento da JVM
			}
		}
		registeredListeners.clear();
	}

	private void handleGcNotification(Notification notification, Object handback) {
		if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(notification.getType())) {
			return;
		}
		if (!(notification.getUserData() instanceof CompositeData cd)) {
			return;
		}
		GarbageCollectionNotificationInfo info = GarbageCollectionNotificationInfo.from(cd);
		GcInfo gcInfo = info.getGcInfo();

		Map<String, MemoryUsage> beforeMap = gcInfo.getMemoryUsageBeforeGc();
		Map<String, MemoryUsage> afterMap = gcInfo.getMemoryUsageAfterGc();

		long heapBeforeBytes = sumHeapBytes(beforeMap);
		long heapAfterBytes = sumHeapBytes(afterMap);
		long freedBytes = Math.max(0L, heapBeforeBytes - heapAfterBytes);

		Map<String, PoolTransition> poolTransitions = buildPoolTransitions(beforeMap, afterMap);

		GcEventRecord record = new GcEventRecord(
				gcInfo.getId(),
				Instant.now().toString(),
				info.getGcName(),
				info.getGcAction(),
				info.getGcCause(),
				gcInfo.getDuration(),
				heapBeforeBytes,
				heapAfterBytes,
				freedBytes,
				poolTransitions);

		recordGcEvent(record);
	}

	/**
	 * Registra um evento de GC no historico em memoria, nas metricas Micrometer,
	 * no log centralizado (Loki) e na sessao de simulacao ativa (se houver).
	 */
	void recordGcEvent(GcEventRecord record) {
		totalGcEventsCaptured.incrementAndGet();

		recentEvents.addLast(record);
		while (recentEvents.size() > MAX_RECENT_EVENTS) {
			recentEvents.pollFirst();
		}

		if (simulationRunning.get()) {
			activeSimulationEvents.add(record);
		}

		Counter.builder("jvm.gc.simulation.events.total")
				.description("Total de eventos de Garbage Collection capturados pelo GcLogService")
				.tag("collector", record.gcName())
				.tag("action", record.gcAction())
				.tag("cause", record.gcCause())
				.register(meterRegistry)
				.increment();

		if (record.freedBytes() > 0) {
			Counter.builder("jvm.gc.simulation.reclaimed.bytes.total")
					.description("Total de bytes liberados no Heap pelas coletas de GC monitoradas")
					.tag("collector", record.gcName())
					.register(meterRegistry)
					.increment(record.freedBytes());
		}

		log.info("[GC-EVENT] id={} collector=\"{}\" action=\"{}\" cause=\"{}\" durationMs={} heapBeforeMb={} heapAfterMb={} freedMb={} pools=\"{}\"",
				record.gcId(),
				record.gcName(),
				record.gcAction(),
				record.gcCause(),
				record.durationMs(),
				record.heapBeforeMb(),
				record.heapAfterMb(),
				record.freedMb(),
				formatPoolsInline(record.poolTransitions()));
	}

	/**
	 * Executa uma simulacao controlada de pressao no Garbage Collector, gravando todos os eventos
	 * ocorridos durante a janela de teste em um arquivo exclusivo dentro de {@code ./gclogs}.
	 *
	 * @param rounds            quantidade de rodadas de alocacao
	 * @param mbPerRound        megabytes alocados por rodada
	 * @param retainPercent     percentual (0 a 50) dos blocos mantidos vivos ate o fim da simulacao
	 *                          para demonstrar promocao para Survivor/Old Gen
	 * @param triggerExplicitGc se {@code true}, invoca {@code System.gc()} ao final das rodadas
	 * @return resultado consolidado da simulacao e caminho do arquivo de log gerado
	 */
	public GcSimulationResult runSimulation(
			int rounds,
			int mbPerRound,
			int retainPercent,
			boolean triggerExplicitGc) {
		if (!simulationRunning.compareAndSet(false, true)) {
			throw new IllegalStateException("Ja existe uma simulacao de GC em andamento. Aguarde a conclusao.");
		}

		activeSimulationEvents.clear();
		Instant startedAt = Instant.now();
		long startNano = System.nanoTime();
		long heapBeforeSimBytes = currentHeapUsedBytes();
		long heapMaxBytes = currentHeapMaxBytes();

		List<byte[]> retainedBlocks = new ArrayList<>();
		long totalAllocatedMb = 0;
		long checksum = 0;

		log.info("Iniciando simulacao de GC: rounds={}, mbPerRound={}, retainPercent={}%, triggerExplicitGc={}",
				rounds, mbPerRound, retainPercent, triggerExplicitGc);

		try {
			for (int round = 1; round <= rounds; round++) {
				List<byte[]> ephemeralRoundBlocks = new ArrayList<>(mbPerRound);
				Map<String, String> shortLivedMap = new LinkedHashMap<>();

				for (int m = 0; m < mbPerRound; m++) {
					byte[] block = new byte[1024 * 1024]; // 1 MB
					ThreadLocalRandom.current().nextBytes(block);
					checksum += block[0];
					totalAllocatedMb++;

					if (retainPercent > 0 && (m * 100 / mbPerRound) < retainPercent) {
						retainedBlocks.add(block);
					} else {
						ephemeralRoundBlocks.add(block);
					}

					// Objetos pequenos de curta duracao para exercitar Eden -> Survivor
					for (int s = 0; s < 250; s++) {
						shortLivedMap.put("sim-r" + round + "-m" + m + "-s" + s, "val-" + System.nanoTime());
					}
				}

				checksum += ephemeralRoundBlocks.size() + shortLivedMap.size();
				// Pequena pausa entre rodadas para permitir que notificacoes assincronas do JMX cheguem
				pauseQuietly(25);
			}

			if (triggerExplicitGc) {
				log.info("Disparando System.gc() explicito ao final da simulacao de GC");
				System.gc();
			}

			// Aguarda brevemente para garantir entrega das notificacoes JMX emitidas pela JVM
			pauseQuietly(120);

			long durationMs = (System.nanoTime() - startNano) / 1_000_000L;
			long heapAfterSimBytes = currentHeapUsedBytes();
			List<GcEventRecord> capturedEvents = List.copyOf(activeSimulationEvents);

			long seq = simulationSequence.incrementAndGet();
			Path reportFile = writeSimulationReport(
					seq,
					startedAt,
					durationMs,
					rounds,
					mbPerRound,
					retainPercent,
					triggerExplicitGc,
					totalAllocatedMb,
					retainedBlocks.size(),
					heapBeforeSimBytes,
					heapAfterSimBytes,
					heapMaxBytes,
					capturedEvents);

			String fullPath = reportFile.toAbsolutePath().toString();
			lastSimulationFilePath.set(fullPath);

			long minorCount = capturedEvents.stream()
					.filter(e -> e.gcAction().toLowerCase(Locale.ROOT).contains("minor"))
					.count();
			long majorCount = capturedEvents.stream()
					.filter(e -> e.gcAction().toLowerCase(Locale.ROOT).contains("major"))
					.count();
			long totalPauseMs = capturedEvents.stream().mapToLong(GcEventRecord::durationMs).sum();
			long totalFreedMb = capturedEvents.stream().mapToLong(GcEventRecord::freedBytes).sum() / BYTES_PER_MB;

			log.info("Simulacao de GC #{} concluida em {} ms: allocatedMb={}, events={}, minorGc={}, majorGc={}, totalPauseMs={}, freedMb={}, file={}",
					seq, durationMs, totalAllocatedMb, capturedEvents.size(), minorCount, majorCount,
					totalPauseMs, totalFreedMb, fullPath);

			return new GcSimulationResult(
					seq,
					startedAt.toString(),
					durationMs,
					rounds,
					mbPerRound,
					retainPercent,
					triggerExplicitGc,
					totalAllocatedMb,
					retainedBlocks.size(),
					heapBeforeSimBytes / BYTES_PER_MB,
					heapAfterSimBytes / BYTES_PER_MB,
					heapMaxBytes / BYTES_PER_MB,
					capturedEvents.size(),
					minorCount,
					majorCount,
					totalPauseMs,
					totalFreedMb,
					checksum,
					fullPath,
					capturedEvents);
		} finally {
			// Libera os blocos retidos ao encerrar a simulacao para nao deixar leak residual
			retainedBlocks.clear();
			activeSimulationEvents.clear();
			simulationRunning.set(false);
		}
	}

	private Path writeSimulationReport(
			long seq,
			Instant startedAt,
			long simulationDurationMs,
			int rounds,
			int mbPerRound,
			int retainPercent,
			boolean triggerExplicitGc,
			long totalAllocatedMb,
			int retainedMbDuringSim,
			long heapBeforeSimBytes,
			long heapAfterSimBytes,
			long heapMaxBytes,
			List<GcEventRecord> events) {
		try {
			Files.createDirectories(outputDir);
			String baseTimestamp = FILE_TS_FORMATTER.format(startedAt);
			Path reportFile = resolveUniqueFilePath(baseTimestamp, seq);

			String reportContent = buildSimulationReportText(
					seq,
					startedAt,
					simulationDurationMs,
					rounds,
					mbPerRound,
					retainPercent,
					triggerExplicitGc,
					totalAllocatedMb,
					retainedMbDuringSim,
					heapBeforeSimBytes,
					heapAfterSimBytes,
					heapMaxBytes,
					events);

			Files.writeString(
					reportFile,
					reportContent,
					StandardCharsets.UTF_8,
					StandardOpenOption.CREATE_NEW,
					StandardOpenOption.WRITE);

			return reportFile;
		} catch (IOException e) {
			throw new IllegalStateException("Falha ao gravar arquivo de log da simulacao de GC em " + outputDir, e);
		}
	}

	private Path resolveUniqueFilePath(String baseTimestamp, long seq) {
		String baseName = String.format("gc-sim-%s-#%d", baseTimestamp, seq);
		Path candidate = outputDir.resolve(baseName + ".log");
		int collisionAttempt = 1;
		while (Files.exists(candidate)) {
			candidate = outputDir.resolve(String.format("%s-%d.log", baseName, collisionAttempt++));
		}
		return candidate;
	}

	private String buildSimulationReportText(
			long seq,
			Instant startedAt,
			long simulationDurationMs,
			int rounds,
			int mbPerRound,
			int retainPercent,
			boolean triggerExplicitGc,
			long totalAllocatedMb,
			int retainedMbDuringSim,
			long heapBeforeSimBytes,
			long heapAfterSimBytes,
			long heapMaxBytes,
			List<GcEventRecord> events) {
		StringBuilder sb = new StringBuilder(8192);
		long minorCount = events.stream()
				.filter(e -> e.gcAction().toLowerCase(Locale.ROOT).contains("minor"))
				.count();
		long majorCount = events.stream()
				.filter(e -> e.gcAction().toLowerCase(Locale.ROOT).contains("major"))
				.count();
		long totalPauseMs = events.stream().mapToLong(GcEventRecord::durationMs).sum();
		long maxPauseMs = events.stream().mapToLong(GcEventRecord::durationMs).max().orElse(0L);
		long totalFreedMb = events.stream().mapToLong(GcEventRecord::freedBytes).sum() / BYTES_PER_MB;
		double gcOverheadPct = simulationDurationMs > 0
				? (totalPauseMs * 100.0) / simulationDurationMs
				: 0.0;

		sb.append("================================================================================\n");
		sb.append("OBSERVABILITY HUB - RELATORIO DE SIMULACAO DO GARBAGE COLLECTOR (JVM)\n");
		sb.append("================================================================================\n");
		sb.append(String.format(Locale.US, "Simulacao #          : %d%n", seq));
		sb.append(String.format(Locale.US, "Timestamp Inicio     : %s%n", startedAt));
		sb.append(String.format(Locale.US, "Duracao Total        : %d ms%n", simulationDurationMs));
		sb.append(String.format(Locale.US, "Parametros           : rounds=%d, mbPerRound=%d MB, retainPercent=%d%%, explicitGc=%s%n",
				rounds, mbPerRound, retainPercent, triggerExplicitGc));
		sb.append(String.format(Locale.US, "Total Alocado        : %d MB (Retido durante a simulacao: %d MB)%n",
				totalAllocatedMb, retainedMbDuringSim));
		sb.append(String.format(Locale.US, "Heap Antes -> Depois : %d MB -> %d MB (Max Heap: %d MB)%n",
				heapBeforeSimBytes / BYTES_PER_MB,
				heapAfterSimBytes / BYTES_PER_MB,
				heapMaxBytes / BYTES_PER_MB));
		sb.append("================================================================================\n\n");

		sb.append("--------------------------------------------------------------------------------\n");
		sb.append("1. LINHA DO TEMPO DE EVENTOS DO GARBAGE COLLECTOR CAPTURADOS\n");
		sb.append("--------------------------------------------------------------------------------\n");
		if (events.isEmpty()) {
			sb.append("Nenhuma coleta de GC foi disparada durante a janela desta simulacao.\n");
			sb.append("Dica: aumente 'rounds' ou 'mbPerRound', ou passe 'triggerExplicitGc=true'.\n");
		} else {
			for (GcEventRecord ev : events) {
				String timeFormatted = formatInstantTime(ev.timestamp());
				sb.append(String.format(Locale.US,
						"[%s] GC #%d | %-20s | %-16s | cause=%-22s | pause=%3d ms | Heap: %3d MB -> %3d MB (-%3d MB) | %s%n",
						timeFormatted,
						ev.gcId(),
						ev.gcName(),
						ev.gcAction(),
						ev.gcCause(),
						ev.durationMs(),
						ev.heapBeforeMb(),
						ev.heapAfterMb(),
						ev.freedMb(),
						formatPoolsInline(ev.poolTransitions())));
			}
		}
		sb.append("\n");

		sb.append("--------------------------------------------------------------------------------\n");
		sb.append("2. RESUMO ESTATISTICO E DIAGNOSTICO DE COMPORTAMENTO DO GC\n");
		sb.append("--------------------------------------------------------------------------------\n");
		sb.append(String.format(Locale.US, "Total de Eventos GC  : %d (%d Minor GC / %d Major GC)%n",
				events.size(), minorCount, majorCount));
		sb.append(String.format(Locale.US, "Tempo Total em Pausa : %d ms (Maior pausa individual: %d ms)%n",
				totalPauseMs, maxPauseMs));
		sb.append(String.format(Locale.US, "Overhead de GC       : %.2f%% do tempo total da simulacao%n", gcOverheadPct));
		sb.append(String.format(Locale.US, "Total Memoria Limpa  : %d MB reciclados pelo GC%n", totalFreedMb));

		if (retainPercent > 0) {
			sb.append("\n[ANALISE]: Com retainPercent > 0%, parte dos objetos sobreviveu ao Minor GC no Eden\n");
			sb.append("e foi promovida para Survivor / Old Gen, reduzindo a taxa de limpeza imediata.\n");
		} else {
			sb.append("\n[ANALISE]: Com retainPercent = 0%, os objetos perderam a referencia ao fim de cada\n");
			sb.append("rodada e puderam ser 100% reciclados nas coletas rapidas (Minor GC / Eden).\n");
		}
		sb.append("================================================================================\n");
		return sb.toString();
	}

	/**
	 * Retorna o status atual do monitor de GC, coletores ativos na JVM, ultimos eventos capturados
	 * e lista de arquivos de log gerados em {@code ./gclogs}.
	 */
	public Map<String, Object> getStatus() {
		List<Map<String, Object>> collectors = ManagementFactory.getGarbageCollectorMXBeans().stream()
				.map(gc -> Map.<String, Object>of(
						"name", gc.getName(),
						"collectionCount", gc.getCollectionCount(),
						"collectionTimeMs", gc.getCollectionTime()))
				.toList();

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("enabled", enabled);
		result.put("simulationRunning", simulationRunning.get());
		result.put("outputDir", outputDir.toAbsolutePath().toString());
		result.put("heapUsedMb", currentHeapUsedBytes() / BYTES_PER_MB);
		result.put("heapMaxMb", currentHeapMaxBytes() / BYTES_PER_MB);
		result.put("totalGcEventsCaptured", totalGcEventsCaptured.get());
		result.put("totalSimulationsRun", simulationSequence.get());
		result.put("lastSimulationFile", lastSimulationFilePath.get() != null ? lastSimulationFilePath.get() : "none");
		result.put("jvmCollectors", collectors);
		result.put("files", listGcLogFiles());
		result.put("recentEvents", List.copyOf(recentEvents));
		return result;
	}

	public List<String> listGcLogFiles() {
		if (!Files.exists(outputDir)) {
			return List.of();
		}
		try (Stream<Path> stream = Files.list(outputDir)) {
			return stream
					.filter(p -> {
						String name = p.getFileName().toString();
						return name.endsWith(".log") || name.startsWith("gc-");
					})
					.map(p -> p.getFileName().toString())
					.sorted(Comparator.reverseOrder())
					.toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	private long sumHeapBytes(Map<String, MemoryUsage> usageMap) {
		if (usageMap == null) {
			return 0L;
		}
		return usageMap.entrySet().stream()
				.filter(entry -> heapPoolNames.contains(entry.getKey()) || isKnownHeapPoolName(entry.getKey()))
				.mapToLong(entry -> entry.getValue().getUsed())
				.sum();
	}

	private boolean isKnownHeapPoolName(String poolName) {
		String lower = poolName.toLowerCase(Locale.ROOT);
		return lower.contains("eden") || lower.contains("survivor") || lower.contains("old")
				|| lower.contains("tenured") || lower.contains("zheap");
	}

	private Map<String, PoolTransition> buildPoolTransitions(
			Map<String, MemoryUsage> beforeMap,
			Map<String, MemoryUsage> afterMap) {
		Map<String, PoolTransition> transitions = new LinkedHashMap<>();
		if (beforeMap == null || afterMap == null) {
			return transitions;
		}
		for (Map.Entry<String, MemoryUsage> entry : beforeMap.entrySet()) {
			String pool = entry.getKey();
			if (!heapPoolNames.contains(pool) && !isKnownHeapPoolName(pool)) {
				continue;
			}
			MemoryUsage before = entry.getValue();
			MemoryUsage after = afterMap.get(pool);
			if (before != null && after != null) {
				transitions.put(pool, new PoolTransition(
						before.getUsed() / BYTES_PER_MB,
						after.getUsed() / BYTES_PER_MB));
			}
		}
		return transitions;
	}

	private String formatPoolsInline(Map<String, PoolTransition> transitions) {
		if (transitions == null || transitions.isEmpty()) {
			return "n/a";
		}
		return transitions.entrySet().stream()
				.map(e -> String.format("%s: %d->%dMB",
						shortenPoolName(e.getKey()),
						e.getValue().beforeMb(),
						e.getValue().afterMb()))
				.collect(Collectors.joining(" | "));
	}

	private String shortenPoolName(String poolName) {
		return poolName.replace("G1 ", "").replace(" Space", "");
	}

	private String formatInstantTime(String isoInstant) {
		try {
			return LOG_TIME_FORMATTER.format(Instant.parse(isoInstant));
		} catch (Exception e) {
			return isoInstant;
		}
	}

	private long currentHeapUsedBytes() {
		return memoryMXBean.getHeapMemoryUsage().getUsed();
	}

	private long currentHeapMaxBytes() {
		long max = memoryMXBean.getHeapMemoryUsage().getMax();
		return max > 0 ? max : memoryMXBean.getHeapMemoryUsage().getCommitted();
	}

	private void pauseQuietly(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private record RegisteredListener(NotificationEmitter emitter, NotificationListener listener) {
	}

	public record PoolTransition(long beforeMb, long afterMb) {
	}

	public record GcEventRecord(
			long gcId,
			String timestamp,
			String gcName,
			String gcAction,
			String gcCause,
			long durationMs,
			long heapBeforeBytes,
			long heapAfterBytes,
			long freedBytes,
			Map<String, PoolTransition> poolTransitions) {

		public long heapBeforeMb() {
			return heapBeforeBytes / BYTES_PER_MB;
		}

		public long heapAfterMb() {
			return heapAfterBytes / BYTES_PER_MB;
		}

		public long freedMb() {
			return freedBytes / BYTES_PER_MB;
		}
	}

	public record GcSimulationResult(
			long simulationNumber,
			String startedAt,
			long simulationDurationMs,
			int rounds,
			int mbPerRound,
			int retainPercent,
			boolean triggerExplicitGc,
			long totalAllocatedMb,
			int retainedMbDuringSim,
			long heapUsedBeforeMb,
			long heapUsedAfterMb,
			long heapMaxMb,
			int gcEventsCaptured,
			long minorGcCount,
			long majorGcCount,
			long totalPauseTimeMs,
			long totalFreedMb,
			long checksum,
			String logFilePath,
			List<GcEventRecord> events) {
	}
}

package com.example.pyroscope.pyroscope;

import java.time.Instant;
import java.util.Map;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import jakarta.annotation.PostConstruct;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * Conecta o Logback ao OpenTelemetry SDK do Spring Boot quando executado sem o javaagent (ex: direto pela IDE),
 * enviando os logs com traceId e spanId via OTLP para o OpenTelemetry Collector -> Loki.
 */
@Configuration
public class OpenTelemetryLogbackConfig {

	private final OpenTelemetry openTelemetry;

	public OpenTelemetryLogbackConfig(OpenTelemetry openTelemetry) {
		this.openTelemetry = openTelemetry;
	}

	@PostConstruct
	public void registerAppender() {
		if (System.getProperty("otel.javaagent.version") != null) {
			return;
		}
		if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext loggerContext)) {
			return;
		}
		AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
			@Override
			protected void append(ILoggingEvent event) {
				LogRecordBuilder builder = openTelemetry.getLogsBridge()
						.get(event.getLoggerName())
						.logRecordBuilder()
						.setTimestamp(Instant.ofEpochMilli(event.getTimeStamp()))
						.setSeverity(toSeverity(event.getLevel()))
						.setSeverityText(event.getLevel().levelStr)
						.setBody(event.getFormattedMessage());

				Map<String, String> mdc = event.getMDCPropertyMap();
				String traceId = mdc != null ? mdc.get("traceId") : null;
				String spanId = mdc != null ? mdc.get("spanId") : null;

				Span currentSpan = Span.current();
				if (currentSpan.getSpanContext().isValid()) {
					builder.setContext(Context.current());
					builder.setAttribute(AttributeKey.stringKey("trace_id"), currentSpan.getSpanContext().getTraceId());
					builder.setAttribute(AttributeKey.stringKey("span_id"), currentSpan.getSpanContext().getSpanId());
				} else if (traceId != null && spanId != null) {
					SpanContext spanContext = SpanContext.create(
							traceId, spanId, TraceFlags.getSampled(), TraceState.getDefault());
					if (spanContext.isValid()) {
						builder.setContext(Context.current().with(Span.wrap(spanContext)));
					}
					builder.setAttribute(AttributeKey.stringKey("trace_id"), traceId);
					builder.setAttribute(AttributeKey.stringKey("span_id"), spanId);
				}

				builder.emit();
			}
		};
		appender.setName("OTEL_SPRING_BRIDGE");
		appender.setContext(loggerContext);
		appender.start();

		Logger rootLogger = loggerContext.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
		rootLogger.addAppender(appender);
	}

	private static Severity toSeverity(Level level) {
		if (level == null) {
			return Severity.UNDEFINED_SEVERITY_NUMBER;
		}
		return switch (level.toInt()) {
			case Level.TRACE_INT -> Severity.TRACE;
			case Level.DEBUG_INT -> Severity.DEBUG;
			case Level.INFO_INT -> Severity.INFO;
			case Level.WARN_INT -> Severity.WARN;
			case Level.ERROR_INT -> Severity.ERROR;
			default -> Severity.INFO;
		};
	}

}

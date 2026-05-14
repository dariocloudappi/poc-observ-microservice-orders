package com.example.ordersapp.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.MDC;
import org.slf4j.Marker;

/**
 * Escribe el nivel del evento en MDC["level"] antes de que Logback lo procese.
 * El agente OTel captura el MDC como atributos del LogRecord (con
 * OTEL_INSTRUMENTATION_LOGBACK_APPENDER_EXPERIMENTAL_CAPTURE_MDC_ATTRIBUTES=*),
 * de modo que "level" llega a New Relic como atributo explícito, no solo como
 * el campo severityText del protocolo OTel.
 */
public class LevelMdcTurboFilter extends TurboFilter {

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level,
                               String format, Object[] params, Throwable t) {
        if (level != null) {
            MDC.put("level", level.toString());
        }
        return FilterReply.NEUTRAL;
    }
}

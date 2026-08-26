package com.example.ordersapp.observability;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.BaggageBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Punto unico para anotar contexto de negocio en la telemetria.
 *
 * EL PROBLEMA QUE RESUELVE
 * ------------------------
 * En OpenTelemetry los atributos de span y los de log son dos flujos
 * distintos que no se hablan entre si:
 *
 *   Span.current().setAttribute("order.id", x)  -> solo aparece en Span
 *   MDC.put("order.id", x)                      -> solo aparece en Log
 *
 * Por eso un `SELECT user.id FROM Log` en New Relic salia vacio aunque el
 * atributo estuviese "puesto": estaba, pero en el otro flujo. Estos metodos
 * escriben en los dos a la vez, de forma que cualquier clave anotada aqui se
 * puede consultar y cruzar tanto en Span como en Log.
 *
 * ADEMAS: los atributos de span NO se heredan. Anotar en el span de servidor
 * no hace que el atributo aparezca en el span de SQL ni en el del cliente
 * HTTP, y tampoco cruza la frontera del servicio. Para eso esta
 * {@link #propagate(Map)}, que usa Baggage: eso si viaja en la cabecera
 * `baggage` del W3C y el agente lo extrae solo en el servicio de destino.
 */
public final class Observability {

    private Observability() {
    }

    /**
     * Anota una clave en el span actual Y en el MDC, para que sea consultable
     * en Span y en Log. Usalo para identificadores y metricas de negocio.
     */
    public static void attr(String key, String value) {
        if (value == null) {
            return;
        }
        Span.current().setAttribute(key, value);
        MDC.put(key, value);
    }

    public static void attr(String key, long value) {
        Span.current().setAttribute(key, value);
        MDC.put(key, String.valueOf(value));
    }

    public static void attr(String key, boolean value) {
        Span.current().setAttribute(key, value);
        MDC.put(key, String.valueOf(value));
    }

    /**
     * Anota SOLO en el span, sin pasar por el MDC.
     *
     * Reservado para datos personales. El nombre y el email de un usuario ya
     * son discutibles en una traza; duplicarlos tambien en cada linea de log
     * multiplica las copias que salen hacia un tercero sin aportar capacidad
     * de diagnostico nueva. Si algun dia hay que quitarlos, este metodo marca
     * exactamente donde estan.
     */
    public static void personalAttr(String key, String value) {
        if (value != null) {
            Span.current().setAttribute(key, value);
        }
    }

    /**
     * Elimina del MDC las claves indicadas. Imprescindible: los hilos de
     * Tomcat se reutilizan, asi que un MDC sin limpiar contamina la siguiente
     * peticion que caiga en el mismo hilo con el order.id de la anterior.
     */
    public static void clear(String... keys) {
        for (String key : keys) {
            MDC.remove(key);
        }
    }

    /**
     * Abre un ambito en el que las claves indicadas viajan en Baggage, es
     * decir, cruzan hacia el siguiente servicio en la cabecera `baggage`.
     *
     * Uso obligatorio con try-with-resources: Baggage se activa sobre el hilo
     * actual y hay que cerrar el ambito en ese mismo hilo.
     *
     *   try (Scope ignored = Observability.propagate(Map.of("order.id", id))) {
     *       restTemplate.getForEntity(...);
     *   }
     */
    public static Scope propagate(Map<String, String> entries) {
        BaggageBuilder builder = Baggage.current().toBuilder();
        entries.forEach((key, value) -> {
            if (value != null) {
                builder.put(key, value);
            }
        });
        return builder.build().makeCurrent();
    }

    /**
     * Copia al span y al MDC el Baggage que ha llegado del servicio llamante.
     * Se invoca una vez por peticion, desde el filtro de entrada, y es lo que
     * hace visible aqui el contexto que marco el servicio de origen.
     *
     * Devuelve las claves escritas para que el filtro pueda limpiarlas.
     */
    public static Map<String, String> adoptIncomingBaggage() {
        Map<String, String> adopted = new LinkedHashMap<>();
        Baggage.current().forEach((key, entry) -> {
            String mdcKey = "baggage." + key;
            Span.current().setAttribute(mdcKey, entry.getValue());
            MDC.put(mdcKey, entry.getValue());
            adopted.put(mdcKey, entry.getValue());
        });
        return adopted;
    }
}

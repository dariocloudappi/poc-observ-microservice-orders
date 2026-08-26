package com.example.ordersapp.services;

import com.example.ordersapp.exceptions.ResourceNotFoundException;
import com.example.ordersapp.models.Order;
import com.example.ordersapp.models.OrderStatus;
import com.example.ordersapp.observability.Observability;
import com.example.ordersapp.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Antes esta clase no anotaba ningun atributo de span: order.id y user.id solo
 * existian dentro del texto del mensaje de log. El efecto era que en New Relic
 * no se podia filtrar ni agrupar una traza por pedido ni por usuario, habia que
 * buscar por subcadena en el mensaje.
 *
 * Ahora todo pasa por {@link Observability}, que escribe en el span y en el MDC
 * a la vez, de forma que la misma clave sirve en Span y en Log.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public List<Order> findByUserId(String userId) {
        Observability.attr("user.id", userId);
        Observability.attr("db.operation", "findByUserId");
        log.debug("Consultando ordenes del usuario {}", userId);
        long start = System.nanoTime();

        List<Order> orders = orderRepository.findByUserId(userId);

        long elapsedMs = elapsedMs(start);
        Observability.attr("order.result_count", orders.size());
        Observability.attr("db.duration_ms", elapsedMs);

        log.atInfo()
                .addKeyValue("user.id", userId)
                .addKeyValue("order.result_count", orders.size())
                .addKeyValue("db.duration_ms", elapsedMs)
                .log("Ordenes obtenidas: usuario={} count={} en {} ms",
                        userId, orders.size(), elapsedMs);
        return orders;
    }

    public List<Order> findByUserIdAndStatus(String userId, OrderStatus status) {
        Observability.attr("user.id", userId);
        Observability.attr("order.status_filter", status.name());
        Observability.attr("db.operation", "findByUserIdAndStatus");
        log.debug("Consultando ordenes del usuario {} con estado {}", userId, status);
        long start = System.nanoTime();

        List<Order> orders = orderRepository.findByUserIdAndStatus(userId, status);

        long elapsedMs = elapsedMs(start);
        Observability.attr("order.result_count", orders.size());
        Observability.attr("db.duration_ms", elapsedMs);

        log.atInfo()
                .addKeyValue("user.id", userId)
                .addKeyValue("order.status_filter", status.name())
                .addKeyValue("order.result_count", orders.size())
                .addKeyValue("db.duration_ms", elapsedMs)
                .log("Ordenes filtradas: usuario={} estado={} count={} en {} ms",
                        userId, status, orders.size(), elapsedMs);
        return orders;
    }

    public Order findById(String orderId, String userId) {
        Observability.attr("order.id", orderId);
        Observability.attr("user.id", userId);
        Observability.attr("db.operation", "findById");
        log.debug("Consultando orden {} del usuario {}", orderId, userId);
        long start = System.nanoTime();

        UUID id;
        try {
            id = UUID.fromString(orderId);
        } catch (IllegalArgumentException e) {
            // Se distingue del 404: un id malformado es un error de contrato
            // del cliente, no un pedido que no existe. Confundirlos oculta
            // clientes que estan construyendo mal las peticiones.
            Observability.attr("error.type", "MalformedOrderId");
            log.atWarn()
                    .addKeyValue("order.id", orderId)
                    .addKeyValue("error.type", "MalformedOrderId")
                    .log("Identificador de orden malformado: {}", orderId);
            throw new ResourceNotFoundException(
                    "Orden '" + orderId + "' no encontrada para el usuario '" + userId + "'");
        }

        Order order = orderRepository.findById(id)
                .filter(o -> o.getUserId().equals(userId))
                .orElseThrow(() -> {
                    Observability.attr("error.type", "OrderNotFound");
                    log.atWarn()
                            .addKeyValue("order.id", orderId)
                            .addKeyValue("user.id", userId)
                            .addKeyValue("error.type", "OrderNotFound")
                            .log("Orden no encontrada o no pertenece al usuario: orden={} usuario={}",
                                    orderId, userId);
                    return new ResourceNotFoundException(
                            "Orden '" + orderId + "' no encontrada para el usuario '" + userId + "'");
                });

        long elapsedMs = elapsedMs(start);
        Observability.attr("order.status", order.getStatus().name());
        Observability.attr("db.duration_ms", elapsedMs);

        log.atInfo()
                .addKeyValue("order.id", orderId)
                .addKeyValue("user.id", userId)
                .addKeyValue("order.status", order.getStatus().name())
                .addKeyValue("db.duration_ms", elapsedMs)
                .log("Orden obtenida: id={} estado={} en {} ms",
                        orderId, order.getStatus(), elapsedMs);
        return order;
    }

    @Transactional
    public Order create(String userId, Order order) {
        Observability.attr("user.id", userId);
        Observability.attr("db.operation", "save");
        log.debug("Creando orden para el usuario {}", userId);
        long start = System.nanoTime();

        order.setUserId(userId);
        order.setStatus(OrderStatus.PENDING);
        order.setTotalAmount(calculateTotal(order));
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        order.getItems().forEach(item -> item.setOrder(order));

        int itemCount = order.getItems().size();
        if (itemCount == 0) {
            log.atWarn()
                    .addKeyValue("user.id", userId)
                    .log("Creando orden sin lineas para el usuario {}", userId);
        }

        Order saved = orderRepository.save(order);

        long elapsedMs = elapsedMs(start);
        Observability.attr("order.id", saved.getId().toString());
        Observability.attr("order.status", saved.getStatus().name());
        Observability.attr("order.item_count", itemCount);
        Observability.attr("order.total_amount", String.valueOf(saved.getTotalAmount()));
        Observability.attr("db.duration_ms", elapsedMs);

        log.atInfo()
                .addKeyValue("order.id", saved.getId().toString())
                .addKeyValue("user.id", userId)
                .addKeyValue("order.item_count", itemCount)
                .addKeyValue("order.total_amount", saved.getTotalAmount())
                .addKeyValue("order.status", saved.getStatus().name())
                .addKeyValue("db.duration_ms", elapsedMs)
                .log("Orden creada: id={} lineas={} total={} en {} ms",
                        saved.getId(), itemCount, saved.getTotalAmount(), elapsedMs);
        return saved;
    }

    @Transactional
    public Order update(String orderId, String userId, Order updated) {
        log.debug("Actualizando orden {} del usuario {}", orderId, userId);
        long start = System.nanoTime();

        Order existing = findById(orderId, userId);
        OrderStatus previousStatus = existing.getStatus();

        existing.getItems().clear();
        updated.getItems().forEach(item -> {
            item.setOrder(existing);
            existing.getItems().add(item);
        });
        existing.setTotalAmount(calculateTotal(existing));
        existing.setStatus(updated.getStatus() != null ? updated.getStatus() : existing.getStatus());
        existing.setUpdatedAt(LocalDateTime.now());

        Order saved = orderRepository.save(existing);

        long elapsedMs = elapsedMs(start);
        boolean statusChanged = previousStatus != saved.getStatus();

        // La transicion de estado es el dato mas util al auditar un pedido y no
        // se puede reconstruir mirando solo el estado final.
        Observability.attr("order.status_previous", previousStatus.name());
        Observability.attr("order.status", saved.getStatus().name());
        Observability.attr("order.status_changed", statusChanged);
        Observability.attr("order.item_count", saved.getItems().size());
        Observability.attr("order.total_amount", String.valueOf(saved.getTotalAmount()));
        Observability.attr("db.duration_ms", elapsedMs);

        log.atInfo()
                .addKeyValue("order.id", orderId)
                .addKeyValue("user.id", userId)
                .addKeyValue("order.status_previous", previousStatus.name())
                .addKeyValue("order.status", saved.getStatus().name())
                .addKeyValue("order.status_changed", statusChanged)
                .addKeyValue("order.item_count", saved.getItems().size())
                .addKeyValue("db.duration_ms", elapsedMs)
                .log("Orden actualizada: id={} estado {} -> {} en {} ms",
                        orderId, previousStatus, saved.getStatus(), elapsedMs);
        return saved;
    }

    @Transactional
    public void delete(String orderId, String userId) {
        log.debug("Eliminando orden {} del usuario {}", orderId, userId);
        long start = System.nanoTime();

        Order existing = findById(orderId, userId);
        OrderStatus statusAtDeletion = existing.getStatus();
        orderRepository.delete(existing);

        long elapsedMs = elapsedMs(start);
        Observability.attr("order.status_at_deletion", statusAtDeletion.name());
        Observability.attr("db.operation", "delete");
        Observability.attr("db.duration_ms", elapsedMs);

        log.atInfo()
                .addKeyValue("order.id", orderId)
                .addKeyValue("user.id", userId)
                .addKeyValue("order.status_at_deletion", statusAtDeletion.name())
                .addKeyValue("db.duration_ms", elapsedMs)
                .log("Orden eliminada: id={} usuario={} estado={} en {} ms",
                        orderId, userId, statusAtDeletion, elapsedMs);
    }

    private double calculateTotal(Order order) {
        return order.getItems().stream()
                .mapToDouble(item -> item.getUnitPrice() * item.getQuantity())
                .sum();
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}

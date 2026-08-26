package com.example.ordersapp.controllers;

import com.example.ordersapp.models.Order;
import com.example.ordersapp.models.OrderStatus;
import com.example.ordersapp.observability.Observability;
import com.example.ordersapp.services.OrderService;
import com.example.ordersapp.services.UserValidationService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Cada endpoint anota api.operation.
 *
 * No es redundante con http.route: la ruta la fija el agente y agrupa por
 * plantilla de URL, mientras que api.operation nombra la operacion de negocio.
 * Es lo que permite un FACET api.operation en NRQL sin depender de como este
 * escrita la ruta, y sigue funcionando si la ruta cambia de version.
 */
@RestController
@RequestMapping("/users/{userId}/orders")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final OrderService orderService;
    private final UserValidationService userValidationService;

    public OrderController(OrderService orderService, UserValidationService userValidationService) {
        this.orderService = orderService;
        this.userValidationService = userValidationService;
    }

    @GetMapping
    public ResponseEntity<List<Order>> getOrders(
            @PathVariable String userId,
            @RequestParam(required = false) OrderStatus status) {
        Observability.attr("api.operation", "orders.list");
        Observability.attr("user.id", userId);
        Observability.attr("order.status_filter", status != null ? status.name() : "none");
        log.debug("Entrando en orders.list: usuario={} estado={}", userId, status);

        userValidationService.validateUser(userId);

        List<Order> orders = status != null
                ? orderService.findByUserIdAndStatus(userId, status)
                : orderService.findByUserId(userId);

        log.atInfo()
                .addKeyValue("api.operation", "orders.list")
                .addKeyValue("user.id", userId)
                .addKeyValue("order.result_count", orders.size())
                .addKeyValue("http.status_code", 200)
                .log("Devolviendo {} ordenes del usuario {}", orders.size(), userId);
        return ResponseEntity.ok(orders);
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<Order> getOrder(
            @PathVariable String userId,
            @PathVariable String orderId) {
        Observability.attr("api.operation", "orders.get");
        Observability.attr("user.id", userId);
        Observability.attr("order.id", orderId);
        log.debug("Entrando en orders.get: usuario={} orden={}", userId, orderId);

        userValidationService.validateUser(userId);
        Order order = orderService.findById(orderId, userId);

        log.atInfo()
                .addKeyValue("api.operation", "orders.get")
                .addKeyValue("user.id", userId)
                .addKeyValue("order.id", orderId)
                .addKeyValue("http.status_code", 200)
                .log("Orden {} devuelta al cliente", orderId);
        return ResponseEntity.ok(order);
    }

    @PostMapping
    public ResponseEntity<Order> createOrder(
            @PathVariable String userId,
            @Valid @RequestBody Order order) {
        Observability.attr("api.operation", "orders.create");
        Observability.attr("user.id", userId);
        log.debug("Entrando en orders.create: usuario={}", userId);

        userValidationService.validateUser(userId);
        Order created = orderService.create(userId, order);

        log.atInfo()
                .addKeyValue("api.operation", "orders.create")
                .addKeyValue("user.id", userId)
                .addKeyValue("order.id", created.getId().toString())
                .addKeyValue("http.status_code", 201)
                .log("Orden creada y devuelta con 201: id={}", created.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{orderId}")
    public ResponseEntity<Order> updateOrder(
            @PathVariable String userId,
            @PathVariable String orderId,
            @Valid @RequestBody Order order) {
        Observability.attr("api.operation", "orders.update");
        Observability.attr("user.id", userId);
        Observability.attr("order.id", orderId);
        log.debug("Entrando en orders.update: usuario={} orden={}", userId, orderId);

        userValidationService.validateUser(userId);
        Order updated = orderService.update(orderId, userId, order);

        log.atInfo()
                .addKeyValue("api.operation", "orders.update")
                .addKeyValue("user.id", userId)
                .addKeyValue("order.id", orderId)
                .addKeyValue("order.status", updated.getStatus().name())
                .addKeyValue("http.status_code", 200)
                .log("Orden {} actualizada, estado final {}", orderId, updated.getStatus());
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{orderId}")
    public ResponseEntity<Void> deleteOrder(
            @PathVariable String userId,
            @PathVariable String orderId) {
        Observability.attr("api.operation", "orders.delete");
        Observability.attr("user.id", userId);
        Observability.attr("order.id", orderId);
        log.debug("Entrando en orders.delete: usuario={} orden={}", userId, orderId);

        userValidationService.validateUser(userId);
        orderService.delete(orderId, userId);

        log.atInfo()
                .addKeyValue("api.operation", "orders.delete")
                .addKeyValue("user.id", userId)
                .addKeyValue("order.id", orderId)
                .addKeyValue("http.status_code", 204)
                .log("Orden {} eliminada, devolviendo 204", orderId);
        return ResponseEntity.noContent().build();
    }
}

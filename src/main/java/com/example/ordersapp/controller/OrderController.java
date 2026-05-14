 package com.example.ordersapp.controller;

import com.example.ordersapp.model.Order;
import com.example.ordersapp.model.OrderStatus;
import com.example.ordersapp.service.OrderService;
import com.example.ordersapp.service.UserValidationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/users/{userId}/orders")
public class OrderController {

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
        userValidationService.validateUser(userId);
        if (status != null) {
            return ResponseEntity.ok(orderService.findByUserIdAndStatus(userId, status));
        }
        return ResponseEntity.ok(orderService.findByUserId(userId));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<Order> getOrder(
            @PathVariable String userId,
            @PathVariable String orderId) {
        userValidationService.validateUser(userId);
        return ResponseEntity.ok(orderService.findById(orderId, userId));
    }

    @PostMapping
    public ResponseEntity<Order> createOrder(
            @PathVariable String userId,
            @Valid @RequestBody Order order) {
        userValidationService.validateUser(userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(orderService.create(userId, order));
    }

    @PutMapping("/{orderId}")
    public ResponseEntity<Order> updateOrder(
            @PathVariable String userId,
            @PathVariable String orderId,
            @Valid @RequestBody Order order) {
        userValidationService.validateUser(userId);
        return ResponseEntity.ok(orderService.update(orderId, userId, order));
    }

    @DeleteMapping("/{orderId}")
    public ResponseEntity<Void> deleteOrder(
            @PathVariable String userId,
            @PathVariable String orderId) {
        userValidationService.validateUser(userId);
        orderService.delete(orderId, userId);
        return ResponseEntity.noContent().build();
    }
}

package com.example.ordersapp.services;

import com.example.ordersapp.exceptions.ResourceNotFoundException;
import com.example.ordersapp.models.Order;
import com.example.ordersapp.models.OrderStatus;
import com.example.ordersapp.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public List<Order> findByUserId(String userId) {
        log.info("Consultando ordenes del usuario {}", userId);
        return orderRepository.findByUserId(userId);
    }

    public List<Order> findByUserIdAndStatus(String userId, OrderStatus status) {
        log.info("Consultando ordenes del usuario {} con estado {}", userId, status);
        return orderRepository.findByUserIdAndStatus(userId, status);
    }

    public Order findById(String orderId, String userId) {
        log.info("Consultando orden {} del usuario {}", orderId, userId);
        UUID id = UUID.fromString(orderId);
        return orderRepository.findById(id)
                .filter(o -> o.getUserId().equals(userId))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Orden '" + orderId + "' no encontrada para el usuario '" + userId + "'"));
    }

    @Transactional
    public Order create(String userId, Order order) {
        order.setUserId(userId);
        order.setStatus(OrderStatus.PENDING);
        order.setTotalAmount(calculateTotal(order));
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        order.getItems().forEach(item -> item.setOrder(order));
        Order saved = orderRepository.save(order);

        log.atInfo()
                .addKeyValue("order.id", saved.getId().toString())
                .addKeyValue("user.id", userId)
                .log("Orden creada");

        return saved;
    }

    @Transactional
    public Order update(String orderId, String userId, Order updated) {
        Order existing = findById(orderId, userId);
        existing.getItems().clear();
        updated.getItems().forEach(item -> {
            item.setOrder(existing);
            existing.getItems().add(item);
        });
        existing.setTotalAmount(calculateTotal(existing));
        existing.setStatus(updated.getStatus() != null ? updated.getStatus() : existing.getStatus());
        existing.setUpdatedAt(LocalDateTime.now());
        Order saved = orderRepository.save(existing);
        log.info("Orden actualizada id={} estado={}", saved.getId(), saved.getStatus());
        return saved;
    }

    @Transactional
    public void delete(String orderId, String userId) {
        Order existing = findById(orderId, userId);
        orderRepository.delete(existing);
        log.info("Orden eliminada id={} usuario={}", orderId, userId);
    }

    private double calculateTotal(Order order) {
        return order.getItems().stream()
                .mapToDouble(item -> item.getUnitPrice() * item.getQuantity())
                .sum();
    }
}

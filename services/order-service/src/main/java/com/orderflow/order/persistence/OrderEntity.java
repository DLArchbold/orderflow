package com.orderflow.order.persistence;

import com.orderflow.order.domain.InvalidTransitionException;
import com.orderflow.order.domain.OrderStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "orders")
public class OrderEntity {
    @Id
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status = OrderStatus.PENDING;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo ASC")
    @org.hibernate.annotations.BatchSize(size = 100)   // a page of orders loads its lines in one extra query, not one per order
    private List<OrderLineEntity> lines = new ArrayList<>();

    protected OrderEntity() {}

    public OrderEntity(UUID id, String customerId) {
        this.id = id;
        this.customerId = customerId;
    }

    public void addLine(String sku, int quantity) {
        lines.add(new OrderLineEntity(this, lines.size(), sku, quantity));
    }

    public void confirm() { transitionTo(OrderStatus.CONFIRMED); }
    public void reject() { transitionTo(OrderStatus.REJECTED); }
    public void cancel() { transitionTo(OrderStatus.CANCELLED); }

    private void transitionTo(OrderStatus next) {
        if (!status.allowedNext().contains(next)) {
            throw new InvalidTransitionException(status, next);
        }
        status = next;
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getCustomerId() { return customerId; }
    public OrderStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<OrderLineEntity> getLines() { return List.copyOf(lines); }
}

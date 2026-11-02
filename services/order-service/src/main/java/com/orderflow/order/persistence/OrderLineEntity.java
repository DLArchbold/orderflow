package com.orderflow.order.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "order_lines")
public class OrderLineEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private OrderEntity order;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(nullable = false)
    private String sku;

    @Column(nullable = false)
    private int quantity;

    protected OrderLineEntity() {}

    OrderLineEntity(OrderEntity order, int lineNo, String sku, int quantity) {
        this.order = order;
        this.lineNo = lineNo;
        this.sku = sku;
        this.quantity = quantity;
    }

    public Long getId() { return id; }
    public int getLineNo() { return lineNo; }
    public String getSku() { return sku; }
    public int getQuantity() { return quantity; }
}

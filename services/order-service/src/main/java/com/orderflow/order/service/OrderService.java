package com.orderflow.order.service;

import com.orderflow.order.outbox.OutboxEvent;
import com.orderflow.order.outbox.OutboxRepository;
import com.orderflow.order.persistence.OrderEntity;
import com.orderflow.order.persistence.OrderRepository;
import com.orderflow.order.web.CreateOrderRequest;
import com.orderflow.order.web.OrderResponse;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;

@Service 
//@Service makes OrderService a bean too
//Why must it be a Bean? See notes\bean-and-spring-dependency-injection.txt
@Transactional
//For why @Transactional is needed, see notes\why_transasctional_is_important_in_order_service.txt
//Basically to ensure transaction session is open for during entire execution of method here 
//and not just in OrderRepository methods to ensure changes in entities here are reflectted in DB as well.
public class OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    //orders only declares a reference; Spring Data JPA will generate a proxy class and implements 
    // OrderRepository interface during startup and registers that as a Bean (inspect for more details);
    // it's assigned in constructor below. OrderRepository handles all DB connections and querying
    // so OrderService doesn't have to.
    private final OrderRepository orders;
    private final OutboxRepository outbox;
    private final ObjectMapper objectMapper;
    private final com.orderflow.order.outbox.OutboxTrace trace;

    public OrderService(OrderRepository orders, OutboxRepository outbox, ObjectMapper objectMapper,
                        com.orderflow.order.outbox.OutboxTrace trace) {
        this.trace = trace;
        // For why OrderRepository is instantiated and can be used automatically without new keyword
        // , see notes\bean-and-spring-dependency-injection.txt
        // Using new keyword here will cause a plain object with no injection and no transactions
        // like supporting implementations like getAll(), etc to be defined.
        this.orders = orders;

        //OutboxRepository needed to insert OutboxEvent in same transaction as OrderEntity to enforce
        //outbox design pattern!!
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    public OrderResponse create(String customerId, CreateOrderRequest request) {
        //For why .save() is still needed even when OrderService is annotated with @Transactional, 
        // see notes\why_transasctional_is_important_in_order_service.txt
        OrderEntity order = new OrderEntity(UUID.randomUUID(), customerId);
        request.items().forEach(item -> order.addLine(item.sku(), item.quantity()));
        OrderResponse response = OrderResponse.from(orders.save(order));

        //Why save OutboxEvent in this transaction? See notes\kafka\significance_of_outbox_pattern.txt
        //And also needed to pass tests in OrderServiceOutboxTest.java
        OutboxEvent outboxEvent = new OutboxEvent(response.id().toString(), "OrderCreated", toJson(response));
        // the publisher runs later on another thread: keep the trace and the correlation id with the row
        outboxEvent.attachContext(trace.capture(), org.slf4j.MDC.get("correlationId"));
        outbox.save(outboxEvent);
        log.info("Order {} created for customer {} with {} line(s), status {}; OrderCreated event written to outbox",
                response.id(), customerId, request.items().size(), response.status());

        return response;
    }


    private String toJson(OrderResponse response){
        try{
            return objectMapper.writeValueAsString(response);
        }catch (JsonProcessingException e){
            throw new IllegalStateException("Could not serialize order event", e);
        }
    }

    //readOnly = true tells Hibernate to skip this snapshot comparison, which is a small saving on list and get
    @Transactional(readOnly = true)
    public List<OrderResponse> list(Caller caller, int page, int size) {
        // newest first, one bounded page: an unbounded findAll() would not survive a real table
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<OrderEntity> visible = caller.admin()
                ? orders.findAll(pageable)
                : orders.findByCustomerId(caller.subject(), pageable);
        return visible.stream().map(OrderResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public OrderResponse get(UUID id, Caller caller) {
        return OrderResponse.from(findOwned(id, caller));
    }

    public OrderResponse cancel(UUID id, Caller caller) {
        OrderEntity order = findOwned(id, caller);
        order.cancel();
        return OrderResponse.from(order);
    }

    // 403 rather than 404 for someone else's order (trade-off written in phase2/docs/security.md)
    private OrderEntity findOwned(UUID id, Caller caller) {
        OrderEntity order = find(id);
        if (!caller.admin() && !order.getCustomerId().equals(caller.subject())) {
            throw new ForbiddenException(id);
        }
        return order;
    }

    private OrderEntity find(UUID id) {
        return orders.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
    }
}

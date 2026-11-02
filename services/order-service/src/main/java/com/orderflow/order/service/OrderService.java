package com.orderflow.order.service;

import com.orderflow.order.persistence.OrderEntity;
import com.orderflow.order.persistence.OrderRepository;
import com.orderflow.order.web.CreateOrderRequest;
import com.orderflow.order.web.OrderResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service 
//@Service makes OrderService a bean too
//Why must it be a Bean? See claude_exports\bean-and-spring-dependency-injection.txt
@Transactional
//For why @Transactional is needed, see claude_exports\why_transasctional_is_important_in_order_service.txt
//Basically to ensure transaction session is open for during entire execution of method here 
//and not just in OrderRepository methods to ensure changes in entities here are reflectted in DB as well.
public class OrderService {
    //orders only declares a reference; Spring Data JPA will generate a proxy class and implements 
    // OrderRepository interface during startup and registers that as a Bean (inspect for more details);
    // it's assigned in constructor below. OrderRepository handles all DB connections and querying
    // so OrderService doesn't have to.
    private final OrderRepository orders;

    public OrderService(OrderRepository orders) {
        // For why OrderRepository is instantiated and can be used automatically without new keyword
        // , see claude_exports\bean-and-spring-dependency-injection.txt
        // Using new keyword here will cause a plain object with no injection and no transactions
        // like supporting implementations like getAll(), etc to be defined.
        this.orders = orders;
    }

    public OrderResponse create(CreateOrderRequest request) {
        //For why .save() is still needed even when OrderService is annotated with @Transactional, 
        // see claude_exports\why_transasctional_is_important_in_order_service.txt
        OrderEntity order = new OrderEntity(UUID.randomUUID(), request.customerId());
        request.items().forEach(item -> order.addLine(item.sku(), item.quantity()));
        return OrderResponse.from(orders.save(order));
    }

    //readOnly = true tells Hibernate to skip this snapshot comparison, which is a small saving on list and get
    @Transactional(readOnly = true)
    public List<OrderResponse> list() {
        return orders.findAll().stream().map((e) -> OrderResponse.from(e)).toList();
    }

    @Transactional(readOnly = true)
    public OrderResponse get(UUID id) {
        return OrderResponse.from(find(id));
    }

    public OrderResponse confirm(UUID id) {
        OrderEntity order = find(id);
        order.confirm();   // dirty checking persists the change at commit
        return OrderResponse.from(order);
    }

    public OrderResponse cancel(UUID id) {
        OrderEntity order = find(id);
        order.cancel();
        return OrderResponse.from(order);
    }

    private OrderEntity find(UUID id) {
        return orders.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
    }
}

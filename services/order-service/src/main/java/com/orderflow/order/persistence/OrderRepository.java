package com.orderflow.order.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

//For why OrderRepository is important, see claude_exports\order_repository_importance.txt
//Summary - this is the only place where the app talks to the DB as OrderService
//doesn't instantiate JDBC connections, SQL etc, all handled by Spring here by extending JpaRepository
    
// For why it's instantiated and can be used automatically without new keyword
// , see claude_exports\bean-and-spring-dependency-injection.txt
public interface OrderRepository extends JpaRepository<OrderEntity, UUID> {}

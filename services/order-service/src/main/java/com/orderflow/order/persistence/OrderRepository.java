package com.orderflow.order.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

//For why OrderRepository is important, see claude_exports\order_repository_importance.txt
//Summary - this is the only place where the app talks to the DB as OrderService
//doesn't instantiate JDBC connections, SQL etc, all handled by Spring here by extending JpaRepository
    
// For why it's instantiated and can be used automatically without new keyword
// , see claude_exports\bean-and-spring-dependency-injection.txt
public interface OrderRepository extends JpaRepository<OrderEntity, UUID> {

    // Fetch each order's lines in the same query (a join) instead of one extra query per order (N+1)
    @Override
    @EntityGraph(attributePaths = "lines")
    //@EntityGraph tells JPA which associations to load together with an entity,
    // for one specific query. It overrides the default fetch strategy set on the mapping, for that query only.
    //We are disabling lazy loading but only for findAll() in order to prevent performance degradation
    // Solves the N+1 problem, see claude_exports\n_pluis_1_problem.txt for elaboration.
    List<OrderEntity> findAll();
}

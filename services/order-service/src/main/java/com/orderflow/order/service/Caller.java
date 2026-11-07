package com.orderflow.order.service;

/** Who is calling: the verified token's subject and whether they hold ROLE_admin. */
public record Caller(String subject, boolean admin) {}

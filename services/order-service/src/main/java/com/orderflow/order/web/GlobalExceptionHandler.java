package com.orderflow.order.web;

import com.orderflow.order.domain.InvalidTransitionException;
import com.orderflow.order.service.OrderNotFoundException;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(OrderNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(com.orderflow.order.service.ForbiddenException.class)
    public ResponseEntity<ErrorResponse> forbidden(com.orderflow.order.service.ForbiddenException e) {
        return error(HttpStatus.FORBIDDEN, e.getMessage());
    }

    // Thrown by OrderEntity.transitionTo when the status change isn't allowed
    @ExceptionHandler(InvalidTransitionException.class)
    public ResponseEntity<ErrorResponse> conflict(InvalidTransitionException e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    // The @Valid rules on CreateOrderRequest
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> invalidBody(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return error(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> malformedBody(HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST, "Malformed request body");
    }

    // e.g. /api/orders?size=0
    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> badParameter(jakarta.validation.ConstraintViolationException e) {
        return error(HttpStatus.BAD_REQUEST, "Invalid request parameter");
    }

    // e.g. /api/orders/not-a-uuid
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> badPathValue(MethodArgumentTypeMismatchException e) {
        return error(HttpStatus.BAD_REQUEST, "Invalid value for '" + e.getName() + "'");
    }

    // inventory-service answered 404 for an unknown SKU (StockClient)
    @ExceptionHandler(org.springframework.web.client.HttpClientErrorException.NotFound.class)
    public ResponseEntity<ErrorResponse> unknownSku(org.springframework.web.client.HttpClientErrorException.NotFound e) {
        return error(HttpStatus.NOT_FOUND, "Unknown SKU");
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(message));
    }
}

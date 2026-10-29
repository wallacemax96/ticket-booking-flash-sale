package com.cielo.ticketbooking.infrastructure.controller;

import com.cielo.ticketbooking.application.dto.ReserveTicketsRequest;
import com.cielo.ticketbooking.domain.exception.ConflictException;
import com.cielo.ticketbooking.domain.exception.ResourceNotFoundException;
import com.cielo.ticketbooking.domain.exception.SoldOutException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.core.MethodParameter;

import java.lang.reflect.Method;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler exceptionHandler;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        exceptionHandler = new GlobalExceptionHandler();
        MockHttpServletRequest mockRequest = new MockHttpServletRequest("POST", "/events/123/reservations");
        request = mockRequest;
    }

    @Test
    @DisplayName("Deve mapear SoldOutException para 409 com Problem Details RFC 7807")
    void shouldMapSoldOutToConflictProblemDetail() {
        ProblemDetail problem = exceptionHandler.handleSoldOut(new SoldOutException("No tickets available"), request);

        assertThat(problem.getType()).isEqualTo(URI.create("about:blank"));
        assertThat(problem.getTitle()).isEqualTo("Tickets Sold Out / Insufficient Stock");
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getDetail()).isEqualTo("No tickets available");
        assertThat(problem.getInstance()).isEqualTo(URI.create("/events/123/reservations"));
    }

    @Test
    @DisplayName("Deve mapear ResourceNotFoundException para 404")
    void shouldMapResourceNotFoundToNotFoundProblemDetail() {
        ProblemDetail problem = exceptionHandler.handleNotFound(
                new ResourceNotFoundException("Event not found"),
                request
        );

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(problem.getTitle()).isEqualTo("Resource Not Found");
        assertThat(problem.getDetail()).isEqualTo("Event not found");
        assertThat(problem.getInstance()).isEqualTo(URI.create("/events/123/reservations"));
    }

    @Test
    @DisplayName("Deve mapear ConflictException para 409")
    void shouldMapConflictToConflictProblemDetail() {
        ProblemDetail problem = exceptionHandler.handleConflict(
                new ConflictException("Idempotency key already used"),
                request
        );

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getTitle()).isEqualTo("Conflict / Business Rule Violation");
        assertThat(problem.getDetail()).isEqualTo("Idempotency key already used");
    }

    @Test
    @DisplayName("Deve retornar os campos inválidos no Problem Details de validação")
    void shouldIncludeInvalidFieldsInBadRequestProblemDetail() throws NoSuchMethodException {
        ReserveTicketsRequest invalidRequest = new ReserveTicketsRequest("customer-123", 0);
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(invalidRequest, "request");
        bindingResult.addError(new FieldError(
                "request",
                "quantity",
                0,
                false,
                null,
                null,
                "Quantity must be at least 1"
        ));
        Method method = GlobalExceptionHandlerTest.class.getDeclaredMethod(
                "requestParameter",
                ReserveTicketsRequest.class
        );
        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(
                new MethodParameter(method, 0),
                bindingResult
        );

        ProblemDetail problem = exceptionHandler.handleValidation(exception, request);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getTitle()).isEqualTo("Bad Request");
        assertThat(problem.getDetail()).isEqualTo("Validation failed");
        assertThat(problem.getProperties()).containsKey("errors");
        assertThat(problem.getInstance()).isEqualTo(URI.create("/events/123/reservations"));
    }

    private void requestParameter(ReserveTicketsRequest request) {
    }
}

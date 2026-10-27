package com.cielo.ticketbooking.application.dto;

import com.cielo.ticketbooking.domain.model.ReservationStatus;
import java.time.Instant;
import java.util.UUID;

public record ReservationResponse(
    UUID id,
    UUID eventId,
    String customerId,
    Integer quantity,
    ReservationStatus status,
    Instant expiresAt,
    Instant createdAt
) {}

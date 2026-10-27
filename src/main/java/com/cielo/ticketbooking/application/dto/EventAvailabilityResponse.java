package com.cielo.ticketbooking.application.dto;

import java.util.UUID;

public record EventAvailabilityResponse(
    UUID id,
    String title,
    Integer totalCapacity,
    Integer availableCapacity
) {}

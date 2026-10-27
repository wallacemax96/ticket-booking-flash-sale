package com.cielo.ticketbooking.application.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateEventRequest(
    @NotBlank(message = "Title is required") String title,
    @NotNull(message = "Total capacity is required") @Min(value = 1, message = "Total capacity must be > 0") Integer totalCapacity
) {}

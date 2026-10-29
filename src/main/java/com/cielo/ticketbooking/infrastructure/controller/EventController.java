package com.cielo.ticketbooking.infrastructure.controller;

import com.cielo.ticketbooking.application.dto.CreateEventRequest;
import com.cielo.ticketbooking.application.dto.EventAvailabilityResponse;
import com.cielo.ticketbooking.application.dto.ReservationResponse;
import com.cielo.ticketbooking.application.dto.ReservationResult;
import com.cielo.ticketbooking.application.dto.ReserveTicketsRequest;
import com.cielo.ticketbooking.application.service.EventService;
import com.cielo.ticketbooking.application.service.ReservationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/events")
public class EventController {
    private final EventService eventService;
    private final ReservationService reservationService;

    public EventController(EventService eventService, ReservationService reservationService) {
        this.eventService = eventService;
        this.reservationService = reservationService;
    }

    @PostMapping
    public ResponseEntity<EventAvailabilityResponse> createEvent(@Valid @RequestBody CreateEventRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(eventService.createEvent(request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<EventAvailabilityResponse> getAvailability(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(eventService.getAvailability(id));
    }

    @PostMapping("/{id}/reservations")
    public ResponseEntity<ReservationResponse> reserveTickets(
            @PathVariable("id") UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ReserveTicketsRequest request) {
        ReservationResult result = reservationService.reserveTickets(id, request, idempotencyKey);
        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(result.reservation());
    }
}

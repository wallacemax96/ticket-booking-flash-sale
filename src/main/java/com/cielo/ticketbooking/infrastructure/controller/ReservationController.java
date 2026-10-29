package com.cielo.ticketbooking.infrastructure.controller;

import com.cielo.ticketbooking.application.dto.ReservationResponse;
import com.cielo.ticketbooking.application.service.ReservationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/reservations")
public class ReservationController {
    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @GetMapping("/{id}")
    public ResponseEntity<ReservationResponse> getReservation(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(reservationService.getReservation(id));
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<ReservationResponse> confirmReservation(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(reservationService.confirmReservation(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancelReservation(@PathVariable("id") UUID id) {
        reservationService.cancelReservation(id);
        return ResponseEntity.noContent().build();
    }
}

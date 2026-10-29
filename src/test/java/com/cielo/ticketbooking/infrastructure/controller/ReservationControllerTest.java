package com.cielo.ticketbooking.infrastructure.controller;

import com.cielo.ticketbooking.application.dto.ReservationResponse;
import com.cielo.ticketbooking.application.service.ReservationService;
import com.cielo.ticketbooking.domain.exception.ConflictException;
import com.cielo.ticketbooking.domain.model.ReservationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReservationController.class)
class ReservationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ReservationService reservationService;

    @Test
    @DisplayName("Deve cancelar reserva e responder com 204 No Content")
    void shouldCancelReservationWithNoContentStatus() throws Exception {
        UUID reservationId = UUID.randomUUID();

        mockMvc.perform(delete("/reservations/{id}", reservationId))
                .andExpect(status().isNoContent());

        verify(reservationService).cancelReservation(reservationId);
    }

    @Test
    @DisplayName("Deve responder 409 ao tentar cancelar reserva confirmada")
    void shouldRejectCancellationOfConfirmedReservation() throws Exception {
        UUID reservationId = UUID.randomUUID();
        doThrow(new ConflictException("Only PENDING reservations can be cancelled"))
                .when(reservationService).cancelReservation(reservationId);

        mockMvc.perform(delete("/reservations/{id}", reservationId))
                .andExpect(status().isConflict());

        verify(reservationService).cancelReservation(reservationId);
    }

    @Test
    @DisplayName("Deve confirmar reserva e responder com 200 OK")
    void shouldConfirmReservationWithOkStatus() throws Exception {
        UUID reservationId = UUID.randomUUID();
        ReservationResponse response = new ReservationResponse(
                reservationId,
                UUID.randomUUID(),
                "customer-123",
                2,
                ReservationStatus.CONFIRMED,
                Instant.now().plusSeconds(600),
                Instant.now()
        );
        when(reservationService.confirmReservation(reservationId)).thenReturn(response);

        mockMvc.perform(post("/reservations/{id}/confirm", reservationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(reservationId.toString()))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        verify(reservationService).confirmReservation(reservationId);
    }
}

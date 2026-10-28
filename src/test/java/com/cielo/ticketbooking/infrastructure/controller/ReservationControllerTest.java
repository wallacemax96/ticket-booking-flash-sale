package com.cielo.ticketbooking.infrastructure.controller;

import com.cielo.ticketbooking.application.service.ReservationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
}

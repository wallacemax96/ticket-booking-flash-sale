package com.cielo.ticketbooking.infrastructure.controller;

import com.cielo.ticketbooking.application.dto.CreateEventRequest;
import com.cielo.ticketbooking.application.dto.EventAvailabilityResponse;
import com.cielo.ticketbooking.application.dto.ReservationResponse;
import com.cielo.ticketbooking.application.dto.ReservationResult;
import com.cielo.ticketbooking.application.dto.ReserveTicketsRequest;
import com.cielo.ticketbooking.application.service.EventService;
import com.cielo.ticketbooking.application.service.ReservationService;
import com.cielo.ticketbooking.domain.exception.ConflictException;
import com.cielo.ticketbooking.domain.exception.SoldOutException;
import com.cielo.ticketbooking.domain.model.ReservationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(EventController.class)
class EventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EventService eventService;

    @MockBean
    private ReservationService reservationService;

    @Test
    @DisplayName("Deve criar evento e responder com 201 Created")
    void shouldCreateEventWithCreatedStatus() throws Exception {
        UUID eventId = UUID.randomUUID();
        CreateEventRequest request = new CreateEventRequest("Show Flash", 100);
        when(eventService.createEvent(request)).thenReturn(
                new EventAvailabilityResponse(eventId, "Show Flash", 100, 100)
        );

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Show Flash","totalCapacity":100}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(eventId.toString()))
                .andExpect(jsonPath("$.title").value("Show Flash"))
                .andExpect(jsonPath("$.totalCapacity").value(100))
                .andExpect(jsonPath("$.availableCapacity").value(100));

        verify(eventService).createEvent(request);
    }

    @Test
    @DisplayName("Deve repassar o header Idempotency-Key ao serviço quando informado")
    void shouldPassIdempotencyHeaderToReservationService() throws Exception {
        UUID eventId = UUID.randomUUID();
        ReservationResponse response = reservationResponse(eventId);
        when(reservationService.reserveTickets(eventId, new ReserveTicketsRequest("customer-123", 2), "idem-123"))
                .thenReturn(new ReservationResult(response, false));

        mockMvc.perform(post("/events/{id}/reservations", eventId)
                        .header("Idempotency-Key", "idem-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":"customer-123","quantity":2}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value(eventId.toString()))
                .andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.status").value("PENDING"));

        verify(reservationService).reserveTickets(eventId, new ReserveTicketsRequest("customer-123", 2), "idem-123");
    }

    @Test
    @DisplayName("Deve responder 200 OK no retry da mesma reserva")
    void shouldReturnOkForIdempotentRetry() throws Exception {
        UUID eventId = UUID.randomUUID();
        ReservationResponse response = reservationResponse(eventId);
        when(reservationService.reserveTickets(eventId, new ReserveTicketsRequest("customer-123", 2), "idem-123"))
                .thenReturn(new ReservationResult(response, true));

        mockMvc.perform(post("/events/{id}/reservations", eventId)
                        .header("Idempotency-Key", "idem-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":"customer-123","quantity":2}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(response.id().toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @DisplayName("Deve repassar null ao serviço quando o header Idempotency-Key estiver ausente")
    void shouldPassNullIdempotencyKeyWhenHeaderIsAbsent() throws Exception {
        UUID eventId = UUID.randomUUID();
        when(reservationService.reserveTickets(eventId, new ReserveTicketsRequest("customer-123", 2), null))
                .thenReturn(new ReservationResult(reservationResponse(eventId), false));

        mockMvc.perform(post("/events/{id}/reservations", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":"customer-123","quantity":2}
                                """))
                .andExpect(status().isCreated());

        verify(reservationService).reserveTickets(eventId, new ReserveTicketsRequest("customer-123", 2), null);
    }

    @Test
    @DisplayName("Deve serializar SoldOutException como Problem Details RFC 7807")
    void shouldReturnProblemDetailWhenReservationIsSoldOut() throws Exception {
        UUID eventId = UUID.randomUUID();
        when(reservationService.reserveTickets(eventId, new ReserveTicketsRequest("customer-123", 2), null))
                .thenThrow(new SoldOutException("No tickets available"));

        mockMvc.perform(post("/events/{id}/reservations", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":"customer-123","quantity":2}
                                """))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Tickets Sold Out / Insufficient Stock"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("No tickets available"))
                .andExpect(jsonPath("$.instance").value("/events/" + eventId + "/reservations"));
    }

    @Test
    @DisplayName("Deve responder 409 ao repetir reserva confirmada ou cancelada")
    void shouldReturnConflictWhenRetryingClosedReservation() throws Exception {
        UUID eventId = UUID.randomUUID();
        when(reservationService.reserveTickets(eventId, new ReserveTicketsRequest("customer-123", 2), "idem-123"))
                .thenThrow(new ConflictException("Cannot retry reservation with status CONFIRMED"));

        mockMvc.perform(post("/events/{id}/reservations", eventId)
                        .header("Idempotency-Key", "idem-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":"customer-123","quantity":2}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("Deve rejeitar payload inválido antes de chamar o serviço de reserva")
    void shouldRejectInvalidReservationBeforeCallingService() throws Exception {
        UUID eventId = UUID.randomUUID();

        mockMvc.perform(post("/events/{id}/reservations", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":null,"quantity":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[?(@.field == 'customerId')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'quantity')]").exists());

        verifyNoInteractions(reservationService);
    }

    private ReservationResponse reservationResponse(UUID eventId) {
        return new ReservationResponse(
                UUID.randomUUID(),
                eventId,
                "customer-123",
                2,
                ReservationStatus.PENDING,
                Instant.parse("2026-10-28T20:00:00Z"),
                Instant.parse("2026-10-28T19:00:00Z")
        );
    }
}

package com.cielo.ticketbooking.application.service;

import com.cielo.ticketbooking.application.dto.ReservationResponse;
import com.cielo.ticketbooking.application.dto.ReservationResult;
import com.cielo.ticketbooking.application.dto.ReserveTicketsRequest;
import com.cielo.ticketbooking.domain.exception.ConflictException;
import com.cielo.ticketbooking.domain.exception.SoldOutException;
import com.cielo.ticketbooking.domain.model.Reservation;
import com.cielo.ticketbooking.domain.model.ReservationStatus;
import com.cielo.ticketbooking.domain.repository.EventRepository;
import com.cielo.ticketbooking.domain.repository.ReservationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationServiceIdempotencyTest {

    private static final String IDEMPOTENCY_KEY = "request-unique-key";

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private EventRepository eventRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final DefaultRedisScript<Long> reserveTicketScript =
            new DefaultRedisScript<>("return 1", Long.class);
    private final DefaultRedisScript<Long> releaseTicketScript =
            new DefaultRedisScript<>("return 1", Long.class);

    private ReservationService reservationService;

    @BeforeEach
    void setUp() {
        reservationService = new ReservationService(
                reservationRepository,
                eventRepository,
                redisTemplate,
                reserveTicketScript,
                releaseTicketScript,
                objectMapper
        );
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void shouldReturnExistingPendingReservationForSameKeyAndPayload() throws Exception {
        UUID eventId = UUID.randomUUID();
        ReserveTicketsRequest request = new ReserveTicketsRequest("customer-1", 1);
        ReservationResponse originalResponse = new ReservationResponse(
                UUID.randomUUID(),
                eventId,
                request.customerId(),
                request.quantity(),
                ReservationStatus.PENDING,
                Instant.parse("2026-10-28T12:10:00Z"),
                Instant.parse("2026-10-28T12:00:00Z")
        );
        Reservation pendingReservation = Reservation.builder()
                .id(originalResponse.id())
                .eventId(eventId)
                .customerId(request.customerId())
                .quantity(request.quantity())
                .status(ReservationStatus.PENDING)
                .expiresAt(originalResponse.expiresAt())
                .createdAt(originalResponse.createdAt())
                .build();
        String redisValue = completedValue(eventId, request, originalResponse);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(false);
        when(valueOperations.get("idempotency:" + IDEMPOTENCY_KEY)).thenReturn(redisValue);
        when(reservationRepository.findById(originalResponse.id())).thenReturn(Optional.of(pendingReservation));

        ReservationResult result = reservationService.reserveTickets(eventId, request, IDEMPOTENCY_KEY);

        assertThat(result.replayed()).isTrue();
        ReservationResponse response = result.reservation();
        assertThat(response.id()).isEqualTo(originalResponse.id());
        assertThat(response.status()).isEqualTo(ReservationStatus.PENDING);
        verify(eventRepository, never()).decrementAvailableCapacity(any(), any(Integer.class));
        verify(reservationRepository, never()).save(any());
        verify(redisTemplate, never()).execute(eq(reserveTicketScript), anyList(), any());
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"CONFIRMED", "CANCELLED"})
    void shouldRejectRetryWhenReservationIsConfirmedOrCancelled(ReservationStatus status) throws Exception {
        UUID eventId = UUID.randomUUID();
        ReserveTicketsRequest request = new ReserveTicketsRequest("customer-1", 1);
        ReservationResponse originalResponse = new ReservationResponse(
                UUID.randomUUID(),
                eventId,
                request.customerId(),
                request.quantity(),
                ReservationStatus.PENDING,
                Instant.parse("2026-10-28T12:10:00Z"),
                Instant.parse("2026-10-28T12:00:00Z")
        );
        Reservation closedReservation = Reservation.builder()
                .id(originalResponse.id())
                .eventId(eventId)
                .customerId(request.customerId())
                .quantity(request.quantity())
                .status(status)
                .expiresAt(originalResponse.expiresAt())
                .createdAt(originalResponse.createdAt())
                .build();
        when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(false);
        when(valueOperations.get("idempotency:" + IDEMPOTENCY_KEY))
                .thenReturn(completedValue(eventId, request, originalResponse));
        when(reservationRepository.findById(originalResponse.id())).thenReturn(Optional.of(closedReservation));

        assertThatThrownBy(() -> reservationService.reserveTickets(eventId, request, IDEMPOTENCY_KEY))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining(status.name());

        verify(eventRepository, never()).decrementAvailableCapacity(any(), any(Integer.class));
        verify(reservationRepository, never()).save(any());
        verify(redisTemplate, never()).execute(eq(reserveTicketScript), anyList(), any());
    }

    @Test
    void shouldRejectSameKeyWhenPayloadDiffers() throws Exception {
        UUID eventId = UUID.randomUUID();
        ReserveTicketsRequest originalRequest = new ReserveTicketsRequest("customer-1", 1);
        ReservationResponse originalResponse = new ReservationResponse(
                UUID.randomUUID(),
                eventId,
                originalRequest.customerId(),
                originalRequest.quantity(),
                ReservationStatus.PENDING,
                Instant.parse("2026-10-28T12:10:00Z"),
                Instant.parse("2026-10-28T12:00:00Z")
        );
        when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(false);
        when(valueOperations.get("idempotency:" + IDEMPOTENCY_KEY))
                .thenReturn(completedValue(eventId, originalRequest, originalResponse));

        assertThatThrownBy(() -> reservationService.reserveTickets(
                eventId,
                new ReserveTicketsRequest("customer-1", 2),
                IDEMPOTENCY_KEY
        )).isInstanceOf(ConflictException.class)
                .hasMessageContaining("different request");

        verify(eventRepository, never()).decrementAvailableCapacity(any(), any(Integer.class));
    }

    @Test
    void shouldRejectDuplicateWhileOriginalRequestIsProcessing() {
        UUID eventId = UUID.randomUUID();
        when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(false);
        when(valueOperations.get("idempotency:" + IDEMPOTENCY_KEY)).thenReturn("PROCESSING");

        assertThatThrownBy(() -> reservationService.reserveTickets(
                eventId,
                new ReserveTicketsRequest("customer-1", 1),
                IDEMPOTENCY_KEY
        )).isInstanceOf(ConflictException.class)
                .hasMessageContaining("currently processing");

        verify(eventRepository, never()).decrementAvailableCapacity(any(), any(Integer.class));
    }

    @Test
    void shouldFinalizeIdempotencyRecordWithOriginalResponseAfterReservation() {
        UUID eventId = UUID.randomUUID();
        ReserveTicketsRequest request = new ReserveTicketsRequest("customer-1", 1);
        when(valueOperations.setIfAbsent(
                eq("idempotency:" + IDEMPOTENCY_KEY),
                anyString(),
                any()
        )).thenReturn(true);
        when(redisTemplate.execute(eq(reserveTicketScript), anyList(), eq("1"))).thenReturn(1L);
        when(eventRepository.decrementAvailableCapacity(eventId, 1)).thenReturn(1);
        when(redisTemplate.execute(
                any(),
                eq(List.of("idempotency:" + IDEMPOTENCY_KEY)),
                any(),
                any(),
                eq("900000")
        )).thenReturn(1L);

        ReservationResult result = reservationService.reserveTickets(eventId, request, IDEMPOTENCY_KEY);
        ReservationResponse response = result.reservation();

        assertThat(result.replayed()).isFalse();
        assertThat(response.customerId()).isEqualTo(request.customerId());
        assertThat(response.eventId()).isEqualTo(eventId);
        verify(reservationRepository).save(any());
        verify(redisTemplate).execute(
                any(),
                eq(List.of("idempotency:" + IDEMPOTENCY_KEY)),
                any(),
                any(),
                eq("900000")
        );
    }

    @Test
    void shouldReleaseIdempotencyClaimWhenReservationFailsForSoldOut() {
        UUID eventId = UUID.randomUUID();
        ReserveTicketsRequest request = new ReserveTicketsRequest("customer-1", 1);
        when(valueOperations.setIfAbsent(
                eq("idempotency:" + IDEMPOTENCY_KEY),
                anyString(),
                any()
        )).thenReturn(true);
        when(redisTemplate.execute(eq(reserveTicketScript), anyList(), eq("1"))).thenReturn(0L);

        assertThatThrownBy(() -> reservationService.reserveTickets(eventId, request, IDEMPOTENCY_KEY))
                .isInstanceOf(SoldOutException.class);

        verify(redisTemplate).execute(
                any(),
                eq(List.of("idempotency:" + IDEMPOTENCY_KEY)),
                anyString()
        );
    }

    private String completedValue(UUID eventId,
                                  ReserveTicketsRequest request,
                                  ReservationResponse response) throws Exception {
        String canonicalRequest = eventId + "\n" + request.customerId() + "\n" + request.quantity();
        String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
        byte[] responseJson = objectMapper.writeValueAsBytes(Map.of(
                "fingerprint", fingerprint,
                "response", response
        ));
        return "COMPLETED:" + fingerprint + ":" +
                Base64.getUrlEncoder().withoutPadding().encodeToString(responseJson);
    }
}

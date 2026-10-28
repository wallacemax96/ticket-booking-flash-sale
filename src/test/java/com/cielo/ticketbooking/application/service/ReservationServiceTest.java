package com.cielo.ticketbooking.application.service;

import com.cielo.ticketbooking.application.dto.ReservationResponse;
import com.cielo.ticketbooking.application.dto.ReserveTicketsRequest;
import com.cielo.ticketbooking.domain.exception.ConflictException;
import com.cielo.ticketbooking.domain.exception.ResourceNotFoundException;
import com.cielo.ticketbooking.domain.exception.SoldOutException;
import com.cielo.ticketbooking.domain.model.Event;
import com.cielo.ticketbooking.domain.model.Reservation;
import com.cielo.ticketbooking.domain.model.ReservationStatus;
import com.cielo.ticketbooking.domain.repository.EventRepository;
import com.cielo.ticketbooking.domain.repository.ReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private EventRepository eventRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private DefaultRedisScript<Long> reserveTicketScript;

    @Mock
    private DefaultRedisScript<Long> releaseTicketScript;

    private ReservationService reservationService;

    private UUID eventId;
    private ReserveTicketsRequest validRequest;

    @BeforeEach
    void setUp() {
        eventId = UUID.randomUUID();
        validRequest = new ReserveTicketsRequest("customer-123", 2);
        reservationService = new ReservationService(
                reservationRepository,
                eventRepository,
                redisTemplate,
                reserveTicketScript,
                releaseTicketScript
        );
    }

    @Test
    @DisplayName("Deve criar reserva com sucesso quando houver saldo no Redis e Postgres")
    void shouldReserveTicketsSuccessfully() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(startsWith("idempotency:"), eq("PROCESSING"), any(Duration.class)))
                .thenReturn(true);

        when(redisTemplate.execute(eq(reserveTicketScript), anyList(), eq("2")))
                .thenReturn(1L);

        when(eventRepository.decrementAvailableCapacity(eventId, 2))
                .thenReturn(1);

        when(reservationRepository.save(any(Reservation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ReservationResponse response = reservationService.reserveTickets(eventId, validRequest, "idemp-key-1");

        assertThat(response).isNotNull();
        assertThat(response.eventId()).isEqualTo(eventId);
        assertThat(response.customerId()).isEqualTo("customer-123");
        assertThat(response.quantity()).isEqualTo(2);
        assertThat(response.status()).isEqualTo(ReservationStatus.PENDING);

        verify(valueOperations).set(eq("idempotency:idemp-key-1"), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("Deve rejeitar reserva com SoldOutException se Redis indicar estoque esgotado")
    void shouldThrowSoldOutExceptionWhenRedisReturnsZero() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(startsWith("idempotency:"), eq("PROCESSING"), any(Duration.class)))
                .thenReturn(true);

        when(redisTemplate.execute(eq(reserveTicketScript), anyList(), eq("2")))
                .thenReturn(0L);

        assertThatThrownBy(() -> reservationService.reserveTickets(eventId, validRequest, "idemp-key-2"))
                .isInstanceOf(SoldOutException.class)
                .hasMessageContaining("Tickets sold out");

        verify(eventRepository, never()).decrementAvailableCapacity(any(), anyInt());
        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("Deve realizar warm-up se Redis indicar chave inexistente (-1) e concluir a reserva")
    void shouldWarmUpCacheWhenRedisKeyMissing() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(startsWith("idempotency:"), eq("PROCESSING"), any(Duration.class)))
                .thenReturn(true);

        // 1ª execução retorna -1 (cache miss), 2ª execução após warm-up retorna 1 (sucesso)
        when(redisTemplate.execute(eq(reserveTicketScript), anyList(), eq("2")))
                .thenReturn(-1L, 1L);

        Event event = Event.builder()
                .id(eventId)
                .title("Show Flash")
                .totalCapacity(100)
                .availableCapacity(50)
                .build();
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));

        when(eventRepository.decrementAvailableCapacity(eventId, 2)).thenReturn(1);
        when(reservationRepository.save(any(Reservation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ReservationResponse response = reservationService.reserveTickets(eventId, validRequest, "idemp-key-3");

        assertThat(response).isNotNull();
        verify(valueOperations).setIfAbsent(eq("event:" + eventId + ":available"), eq("50"));
        verify(eventRepository).decrementAvailableCapacity(eventId, 2);
    }

    @Test
    @DisplayName("Deve fazer rollback no Redis se o decremento no Postgres falhar (zero linhas afetadas)")
    void shouldRollbackRedisStockWhenPostgresUpdateFails() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(startsWith("idempotency:"), eq("PROCESSING"), any(Duration.class)))
                .thenReturn(true);

        when(redisTemplate.execute(eq(reserveTicketScript), anyList(), eq("2"))).thenReturn(1L);
        when(eventRepository.decrementAvailableCapacity(eventId, 2)).thenReturn(0);

        assertThatThrownBy(() -> reservationService.reserveTickets(eventId, validRequest, "idemp-key-4"))
                .isInstanceOf(SoldOutException.class);

        verify(redisTemplate).execute(eq(releaseTicketScript), anyList(), eq("2"));
        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("Deve lançar ConflictException quando a chave de idempotência já estiver registrada")
    void shouldThrowConflictWhenIdempotencyKeyAlreadyExists() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(startsWith("idempotency:"), eq("PROCESSING"), any(Duration.class)))
                .thenReturn(false);
        when(valueOperations.get("idempotency:key-duplicated")).thenReturn("res-existing-id");

        assertThatThrownBy(() -> reservationService.reserveTickets(eventId, validRequest, "key-duplicated"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already been processed");

        verify(redisTemplate, never()).execute(any(), anyList(), anyString());
    }

    @Test
    @DisplayName("Deve cancelar reserva PENDING e estornar cota no banco e no Redis")
    void shouldCancelReservationAndRestoreStock() {
        UUID reservationId = UUID.randomUUID();
        Reservation reservation = Reservation.builder()
                .id(reservationId)
                .eventId(eventId)
                .customerId("customer-123")
                .quantity(3)
                .status(ReservationStatus.PENDING)
                .expiresAt(Instant.now().plus(Duration.ofMinutes(5)))
                .build();

        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));

        reservationService.cancelReservation(reservationId);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        verify(reservationRepository).save(reservation);
        verify(eventRepository).incrementAvailableCapacity(eventId, 3);
        verify(redisTemplate).execute(eq(releaseTicketScript), anyList(), eq("3"));
    }

    @Test
    @DisplayName("Scheduler deve expirar reservas vencidas e devolver cota")
    void shouldExpirePendingReservations() {
        UUID resId = UUID.randomUUID();
        Reservation expiredReservation = Reservation.builder()
                .id(resId)
                .eventId(eventId)
                .quantity(4)
                .status(ReservationStatus.PENDING)
                .expiresAt(Instant.now().minus(Duration.ofMinutes(1)))
                .build();

        when(reservationRepository.findByStatusAndExpiresAtBefore(eq(ReservationStatus.PENDING), any(Instant.class)))
                .thenReturn(List.of(expiredReservation));

        reservationService.expirePendingReservations();

        assertThat(expiredReservation.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        verify(eventRepository).incrementAvailableCapacity(eventId, 4);
        verify(redisTemplate).execute(eq(releaseTicketScript), anyList(), eq("4"));
    }
}
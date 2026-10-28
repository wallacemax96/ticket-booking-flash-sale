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
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final ReservationRepository reservationRepository;
    private final EventRepository eventRepository;
    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> reserveTicketScript;
    private final DefaultRedisScript<Long> releaseTicketScript;

    public ReservationService(ReservationRepository reservationRepository,
                              EventRepository eventRepository,
                              StringRedisTemplate redisTemplate,
                              DefaultRedisScript<Long> reserveTicketScript,
                              DefaultRedisScript<Long> releaseTicketScript) {
        this.reservationRepository = reservationRepository;
        this.eventRepository = eventRepository;
        this.redisTemplate = redisTemplate;
        this.reserveTicketScript = reserveTicketScript;
        this.releaseTicketScript = releaseTicketScript;
    }

    @Transactional
    public ReservationResponse reserveTickets(UUID eventId, ReserveTicketsRequest request, String idempotencyKey) {
        // 1. Verificação de Idempotência
        checkIdempotency(idempotencyKey);

        String stockKey = "event:" + eventId + ":available";

        // 2. Barreira Atômica no Redis com Warm-up Lazy (Fallback)
        decrementRedisStockWithWarmUp(eventId, stockKey, request.quantity());

        try {
            // 3. Verificação e Decremento Atômico Relacional no PostgreSQL
            int rowsUpdated = eventRepository.decrementAvailableCapacity(eventId, request.quantity());
            if (rowsUpdated == 0) {
                // Compensação imediata no Redis se o banco rejeitou por saldo
                rollbackRedisStock(stockKey, request.quantity());
                throw new SoldOutException("Tickets sold out in database consistency check");
            }

            // 4. Criação da Reserva PENDING (TTL de 10 min)
            UUID reservationId = UUID.randomUUID();
            Reservation reservation = Reservation.builder()
                    .id(reservationId)
                    .eventId(eventId)
                    .customerId(request.customerId())
                    .quantity(request.quantity())
                    .status(ReservationStatus.PENDING)
                    .expiresAt(Instant.now().plus(Duration.ofMinutes(10)))
                    .build();

            reservationRepository.save(reservation);

            // 5. Registra conclusão da chave de idempotência
            finalizeIdempotency(idempotencyKey, reservationId);

            return toResponse(reservation);

        } catch (Exception ex) {
            // Rollback defensivo no Redis em caso de falha de I/O ou erro inesperado
            if (!(ex instanceof SoldOutException)) {
                rollbackRedisStock(stockKey, request.quantity());
            }
            throw ex;
        }
    }

    private void decrementRedisStockWithWarmUp(UUID eventId, String stockKey, int quantity) {
        Long quantityAvailable = executeReservationScript(stockKey, quantity);

        // Chave ausente no Redis (-1): faz reaquecimento direto do banco relacional
        if (quantityAvailable != null && quantityAvailable == -1L) {
            log.warn("Cache miss on Redis key {}. Warming up from PostgreSQL...", stockKey);
            warmUpRedisStock(eventId, stockKey);

            // Reexecuta o script após o reaquecimento
            quantityAvailable = executeReservationScript(stockKey, quantity);
        }

        if (quantityAvailable == null || quantityAvailable == -1L) {
            throw new ResourceNotFoundException("Event not found with ID: " + eventId);
        }

        if (quantityAvailable == 0L) {
            throw new SoldOutException("Tickets sold out or requested quantity exceeds available capacity");
        }

    }

    private Long executeReservationScript(String stockKey, int quantity) {
        return redisTemplate.execute(
                reserveTicketScript,
                Collections.singletonList(stockKey),
                String.valueOf(quantity)
        );
    }

    private void warmUpRedisStock(UUID eventId, String stockKey) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found with ID: " + eventId));

        // SETNX: Só preenche se outra instância paralela ainda não tiver aquecido
        redisTemplate.opsForValue().setIfAbsent(stockKey, String.valueOf(event.getAvailableCapacity()));
    }

    private void rollbackRedisStock(String stockKey, int quantity) {
        try {
            redisTemplate.execute(releaseTicketScript, Collections.singletonList(stockKey), String.valueOf(quantity));
        } catch (Exception ex) {
            log.error("Failed to release Redis stock rollback on key {}: {}", stockKey, ex.getMessage());
        }
    }

    private void checkIdempotency(String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            String lockKey = "idempotency:" + idempotencyKey;
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "PROCESSING", Duration.ofMinutes(15));
            if (Boolean.FALSE.equals(acquired)) {
                String existing = redisTemplate.opsForValue().get(lockKey);
                if (!"PROCESSING".equals(existing) && existing != null) {
                    throw new ConflictException("Request with this idempotency key has already been processed");
                }
                throw new ConflictException("Concurrent request with same idempotency key is currently processing");
            }
        }
    }

    private void finalizeIdempotency(String idempotencyKey, UUID reservationId) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            redisTemplate.opsForValue().set("idempotency:" + idempotencyKey, reservationId.toString(), Duration.ofMinutes(15));
        }
    }

    @Transactional(readOnly = true)
    public ReservationResponse getReservation(UUID reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation not found with ID: " + reservationId));
        return toResponse(reservation);
    }

    @Transactional
    public void cancelReservation(UUID reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation not found with ID: " + reservationId));

        if (reservation.getStatus() != ReservationStatus.PENDING && reservation.getStatus() != ReservationStatus.CONFIRMED) {
            throw new ConflictException("Reservation cannot be cancelled in state: " + reservation.getStatus());
        }

        reservation.setStatus(ReservationStatus.CANCELLED);
        reservation.setUpdatedAt(Instant.now());
        reservationRepository.save(reservation);

        eventRepository.incrementAvailableCapacity(reservation.getEventId(), reservation.getQuantity());

        String stockKey = "event:" + reservation.getEventId() + ":available";
        rollbackRedisStock(stockKey, reservation.getQuantity());
    }

    @Scheduled(fixedDelay = 30000)
    @SchedulerLock(name = "expireReservationsTask", lockAtLeastFor = "PT10S", lockAtMostFor = "PT25S")
    @Transactional
    public void expirePendingReservations() {
        List<Reservation> expiredReservations = reservationRepository.findByStatusAndExpiresAtBefore(
                ReservationStatus.PENDING, Instant.now()
        );

        for (Reservation r : expiredReservations) {
            r.setStatus(ReservationStatus.EXPIRED);
            r.setUpdatedAt(Instant.now());
            reservationRepository.save(r);

            eventRepository.incrementAvailableCapacity(r.getEventId(), r.getQuantity());

            String stockKey = "event:" + r.getEventId() + ":available";
            rollbackRedisStock(stockKey, r.getQuantity());
        }
    }

    private ReservationResponse toResponse(Reservation r) {
        return new ReservationResponse(
                r.getId(),
                r.getEventId(),
                r.getCustomerId(),
                r.getQuantity(),
                r.getStatus(),
                r.getExpiresAt(),
                r.getCreatedAt()
        );
    }
}
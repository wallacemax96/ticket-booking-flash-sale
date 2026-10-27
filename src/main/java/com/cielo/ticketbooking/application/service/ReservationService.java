package com.cielo.ticketbooking.application.service;

import com.cielo.ticketbooking.application.dto.ReservationResponse;
import com.cielo.ticketbooking.application.dto.ReserveTicketsRequest;
import com.cielo.ticketbooking.domain.exception.ConflictException;
import com.cielo.ticketbooking.domain.exception.ResourceNotFoundException;
import com.cielo.ticketbooking.domain.exception.SoldOutException;
import com.cielo.ticketbooking.domain.model.Reservation;
import com.cielo.ticketbooking.domain.model.ReservationStatus;
import com.cielo.ticketbooking.domain.repository.EventRepository;
import com.cielo.ticketbooking.domain.repository.ReservationRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
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
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            String lockKey = "idempotency:" + idempotencyKey;
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "PROCESSING", Duration.ofMinutes(15));
            if (Boolean.FALSE.equals(acquired)) {
                String existing = redisTemplate.opsForValue().get(lockKey);
                if (!"PROCESSING".equals(existing) && existing != null) {
                    return getReservation(UUID.fromString(existing));
                }
                throw new ConflictException("Concurrent request with same idempotency key is processing");
            }
        }

        String stockKey = "event:" + eventId + ":available";
        Long result = redisTemplate.execute(reserveTicketScript, Collections.singletonList(stockKey), String.valueOf(request.quantity()));

        if (result == null || result == -1L) throw new ResourceNotFoundException("Event not found or stock uninitialized");
        if (result == 0L) throw new SoldOutException("Tickets sold out or requested quantity exceeds available capacity");

        try {
            int rowsUpdated = eventRepository.decrementAvailableCapacity(eventId, request.quantity());
            if (rowsUpdated == 0) throw new SoldOutException("Tickets sold out in database check");

            Reservation reservation = Reservation.builder()
                    .id(UUID.randomUUID())
                    .eventId(eventId)
                    .customerId(request.customerId())
                    .quantity(request.quantity())
                    .status(ReservationStatus.PENDING)
                    .expiresAt(Instant.now().plus(Duration.ofMinutes(10)))
                    .build();

            reservationRepository.save(reservation);

            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                redisTemplate.opsForValue().set("idempotency:" + idempotencyKey, reservation.getId().toString(), Duration.ofMinutes(15));
            }

            return toResponse(reservation);
        } catch (Exception ex) {
            redisTemplate.execute(releaseTicketScript, Collections.singletonList(stockKey), String.valueOf(request.quantity()));
            throw ex;
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
        redisTemplate.execute(releaseTicketScript, Collections.singletonList("event:" + reservation.getEventId() + ":available"), String.valueOf(reservation.getQuantity()));
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
            redisTemplate.execute(releaseTicketScript, Collections.singletonList("event:" + r.getEventId() + ":available"), String.valueOf(r.getQuantity()));
        }
    }

    private ReservationResponse toResponse(Reservation r) {
        return new ReservationResponse(r.getId(), r.getEventId(), r.getCustomerId(), r.getQuantity(), r.getStatus(), r.getExpiresAt(), r.getCreatedAt());
    }
}

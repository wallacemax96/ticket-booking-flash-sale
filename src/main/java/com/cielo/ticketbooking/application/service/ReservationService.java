package com.cielo.ticketbooking.application.service;

import com.cielo.ticketbooking.application.dto.ReservationResponse;
import com.cielo.ticketbooking.application.dto.ReservationResult;
import com.cielo.ticketbooking.application.dto.ReserveTicketsRequest;
import com.cielo.ticketbooking.domain.exception.ConflictException;
import com.cielo.ticketbooking.domain.exception.ResourceNotFoundException;
import com.cielo.ticketbooking.domain.exception.SoldOutException;
import com.cielo.ticketbooking.domain.model.Event;
import com.cielo.ticketbooking.domain.model.Reservation;
import com.cielo.ticketbooking.domain.model.ReservationStatus;
import com.cielo.ticketbooking.domain.repository.EventRepository;
import com.cielo.ticketbooking.domain.repository.ReservationRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);
    private static final Duration IDEMPOTENCY_TTL = Duration.ofMinutes(15);
    private static final String IDEMPOTENCY_PREFIX = "idempotency:";
    private static final String PROCESSING_PREFIX = "PROCESSING:";
    private static final String COMPLETED_PREFIX = "COMPLETED:";
    private static final DefaultRedisScript<Long> RELEASE_IDEMPOTENCY_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                    "return redis.call('del', KEYS[1]) else return 0 end",
            Long.class
    );
    private static final DefaultRedisScript<Long> COMPLETE_IDEMPOTENCY_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                    "redis.call('set', KEYS[1], ARGV[2], 'PX', ARGV[3]); return 1 else return 0 end",
            Long.class
    );

    private final ReservationRepository reservationRepository;
    private final EventRepository eventRepository;
    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> reserveTicketScript;
    private final DefaultRedisScript<Long> releaseTicketScript;
    private final ObjectMapper objectMapper;

    public ReservationService(ReservationRepository reservationRepository,
                              EventRepository eventRepository,
                              StringRedisTemplate redisTemplate,
                              DefaultRedisScript<Long> reserveTicketScript,
                              DefaultRedisScript<Long> releaseTicketScript,
                              ObjectMapper objectMapper) {
        this.reservationRepository = reservationRepository;
        this.eventRepository = eventRepository;
        this.redisTemplate = redisTemplate;
        this.reserveTicketScript = reserveTicketScript;
        this.releaseTicketScript = releaseTicketScript;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ReservationResult reserveTickets(UUID eventId, ReserveTicketsRequest request, String idempotencyKey) {
        IdempotencyCheck idempotencyCheck = checkIdempotency(eventId, request, idempotencyKey);
        if (idempotencyCheck.replayResponse() != null) {
            return new ReservationResult(idempotencyCheck.replayResponse(), true);
        }
        IdempotencyClaim claim = idempotencyCheck.claim();
        AtomicReference<ReservationResponse> responseAfterCommit = registerIdempotencyCompletion(claim);

        String stockKey = "event:" + eventId + ":available";
        boolean redisStockReserved = false;

        try {
            // Redis rejeita rapidamente a disputa; PostgreSQL confirma o saldo com atualização condicional.
            decrementRedisStockWithWarmUp(eventId, stockKey, request.quantity());
            redisStockReserved = true;

            int rowsUpdated = eventRepository.decrementAvailableCapacity(eventId, request.quantity());
            if (rowsUpdated == 0) {
                // O banco é a última barreira contra oversell; restaura-se o contador Redis se ele rejeitar.
                rollbackRedisStock(stockKey, request.quantity());
                redisStockReserved = false;
                throw new SoldOutException("Tickets sold out in database consistency check");
            }

            // A reserva permanece PENDING por até 10 minutos para pagamento ou expiração.
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

            ReservationResponse response = toResponse(reservation);
            if (claim != null) {
                responseAfterCommit.set(response);
                if (!TransactionSynchronizationManager.isSynchronizationActive()) {
                    finalizeIdempotency(claim, response);
                }
            }
            return new ReservationResult(response, false);

        } catch (Exception ex) {
            // Se a transação não concluir, desfaz no Redis a cota já debitada.
            if (redisStockReserved) {
                rollbackRedisStock(stockKey, request.quantity());
            }
            if (claim != null && !TransactionSynchronizationManager.isSynchronizationActive()) {
                releaseIdempotency(claim);
            }
            throw ex;
        }
    }

    private void decrementRedisStockWithWarmUp(UUID eventId, String stockKey, int quantity) {
        Long quantityAvailable = executeReservationScript(stockKey, quantity);

        // Em cache miss, recarrega o contador a partir da fonte persistente antes de tentar novamente.
        if (quantityAvailable != null && quantityAvailable == -1L) {
            log.warn("Cache miss on Redis key {}. Warming up from PostgreSQL...", stockKey);
            warmUpRedisStock(eventId, stockKey);

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

        // SETNX evita sobrescrever o contador caso outra instância já tenha aquecido a chave.
        redisTemplate.opsForValue().setIfAbsent(stockKey, String.valueOf(event.getAvailableCapacity()));
    }

    private void rollbackRedisStock(String stockKey, int quantity) {
        try {
            redisTemplate.execute(releaseTicketScript, Collections.singletonList(stockKey), String.valueOf(quantity));
        } catch (Exception ex) {
            log.error("Failed to release Redis stock rollback on key {}: {}", stockKey, ex.getMessage());
        }
    }

    private IdempotencyCheck checkIdempotency(UUID eventId,
                                              ReserveTicketsRequest request,
                                              String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return new IdempotencyCheck(null, null);
        }

        String redisKey = IDEMPOTENCY_PREFIX + idempotencyKey;
        String requestFingerprint = fingerprint(eventId, request);
        String token = UUID.randomUUID().toString();
        String processingValue = PROCESSING_PREFIX + token + ":" + requestFingerprint;
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(redisKey, processingValue, IDEMPOTENCY_TTL);
        if (Boolean.TRUE.equals(acquired)) {
            return new IdempotencyCheck(
                    new IdempotencyClaim(redisKey, processingValue, requestFingerprint),
                    null
            );
        }
        if (acquired == null) {
            throw new IllegalStateException("Redis did not confirm the idempotency claim");
        }

        String existing = redisTemplate.opsForValue().get(redisKey);
        if (existing == null || "PROCESSING".equals(existing) || existing.startsWith(PROCESSING_PREFIX)) {
            throw new ConflictException("Concurrent request with same idempotency key is currently processing");
        }

        CachedIdempotencyResult cachedResult = parseCompletedResult(existing);
        if (cachedResult != null) {
            if (!cachedResult.fingerprint().equals(requestFingerprint)) {
                throw new ConflictException("Idempotency key was already used with a different request");
            }
            // Recarrega o estado atual para não devolver PENDING após a reserva ser confirmada.
            Reservation reservation = reservationRepository.findById(cachedResult.response().id())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Reservation not found with ID: " + cachedResult.response().id()
                    ));
            return new IdempotencyCheck(null, toRetryResponse(reservation));
        }

        Reservation legacyReservation = findLegacyReservation(existing);
        if (legacyReservation != null) {
            if (!legacyReservation.getEventId().equals(eventId)
                    || !legacyReservation.getCustomerId().equals(request.customerId())
                    || !legacyReservation.getQuantity().equals(request.quantity())) {
                throw new ConflictException("Idempotency key was already used with a different request");
            }
            return new IdempotencyCheck(null, toRetryResponse(legacyReservation));
        }

        throw new ConflictException("Request with this idempotency key has already been processed");
    }

    private CachedIdempotencyResult parseCompletedResult(String value) {
        if (!value.startsWith(COMPLETED_PREFIX)) {
            return null;
        }
        String[] parts = value.split(":", 3);
        if (parts.length != 3) {
            throw new IllegalStateException("Invalid completed idempotency record in Redis");
        }
        try {
            byte[] json = java.util.Base64.getUrlDecoder().decode(parts[2]);
            CachedIdempotencyResult result = objectMapper.readValue(
                    json,
                    CachedIdempotencyResult.class
            );
            if (!parts[1].equals(result.fingerprint())) {
                throw new IllegalStateException("Idempotency record fingerprint does not match");
            }
            return result;
        } catch (IOException | IllegalArgumentException ex) {
            throw new IllegalStateException("Could not read completed idempotency record from Redis", ex);
        }
    }

    private Reservation findLegacyReservation(String value) {
        try {
            return reservationRepository.findById(UUID.fromString(value)).orElse(null);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private AtomicReference<ReservationResponse> registerIdempotencyCompletion(IdempotencyClaim claim) {
        AtomicReference<ReservationResponse> response = new AtomicReference<>();
        if (claim != null && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    ReservationResponse completedResponse = response.get();
                    if (completedResponse == null) {
                        releaseIdempotency(claim);
                    } else {
                        // Só publica o resultado replayável após a reserva estar confirmada no banco.
                        finalizeIdempotency(claim, completedResponse);
                    }
                }

                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        releaseIdempotency(claim);
                    }
                }
            });
        }
        return response;
    }

    private void finalizeIdempotency(IdempotencyClaim claim, ReservationResponse response) {
        String cachedResponse;
        try {
            byte[] json = objectMapper.writeValueAsBytes(
                    new CachedIdempotencyResult(claim.requestFingerprint(), response)
            );
            cachedResponse = COMPLETED_PREFIX + claim.requestFingerprint() + ":" +
                    java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize idempotent reservation response", ex);
        }

        try {
            // A comparação do token impede que uma tentativa antiga finalize a chave de outra chamada.
            Long finalized = redisTemplate.execute(
                    COMPLETE_IDEMPOTENCY_SCRIPT,
                    Collections.singletonList(claim.redisKey()),
                    claim.processingValue(),
                    cachedResponse,
                    String.valueOf(IDEMPOTENCY_TTL.toMillis())
            );
            if (!Long.valueOf(1L).equals(finalized)) {
                log.error("Idempotency claim could not be finalized after reservation commit");
            }
        } catch (DataAccessException ex) {
            log.error("Failed to finalize idempotency claim after reservation commit", ex);
        }
    }

    private void releaseIdempotency(IdempotencyClaim claim) {
        try {
            // Remove apenas a claim desta tentativa, sem desbloquear uma chamada concorrente mais nova.
            redisTemplate.execute(
                    RELEASE_IDEMPOTENCY_SCRIPT,
                    Collections.singletonList(claim.redisKey()),
                    claim.processingValue()
            );
        } catch (DataAccessException ex) {
            log.error("Failed to release idempotency claim after reservation failure", ex);
        }
    }

    private String fingerprint(UUID eventId, ReserveTicketsRequest request) {
        String canonicalRequest = eventId + "\n" + request.customerId() + "\n" + request.quantity();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalRequest.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private record IdempotencyCheck(IdempotencyClaim claim, ReservationResponse replayResponse) {
    }

    private record IdempotencyClaim(String redisKey, String processingValue, String requestFingerprint) {
    }

    private record CachedIdempotencyResult(String fingerprint, ReservationResponse response) {
    }

    @Transactional(readOnly = true)
    public ReservationResponse getReservation(UUID reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation not found with ID: " + reservationId));
        return toResponse(reservation);
    }

    @Transactional
    public ReservationResponse confirmReservation(UUID reservationId) {
        Instant confirmedAt = Instant.now();
        int updatedRows = reservationRepository.confirmPendingReservation(
                reservationId,
                ReservationStatus.PENDING,
                ReservationStatus.CONFIRMED,
                confirmedAt
        );
        if (updatedRows == 0) {
            reservationRepository.findById(reservationId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Reservation not found with ID: " + reservationId
                    ));
            throw new ConflictException("Only a non-expired PENDING reservation can be confirmed");
        }

        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Reservation not found with ID: " + reservationId
                ));
        return toResponse(reservation);
    }

    @Transactional
    public void cancelReservation(UUID reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation not found with ID: " + reservationId));

        Instant cancelledAt = Instant.now();
        int updatedRows = reservationRepository.cancelPendingReservation(
                reservationId,
                ReservationStatus.PENDING,
                ReservationStatus.CANCELLED,
                cancelledAt
        );
        if (updatedRows == 0) {
            throw new ConflictException("Only PENDING reservations can be cancelled");
        }

        // A transição condicional impede cancelar uma reserva confirmada ou devolver sua cota.
        reservation.setStatus(ReservationStatus.CANCELLED);
        reservation.setUpdatedAt(cancelledAt);

        eventRepository.incrementAvailableCapacity(reservation.getEventId(), reservation.getQuantity());

        String stockKey = "event:" + reservation.getEventId() + ":available";
        rollbackRedisStock(stockKey, reservation.getQuantity());
    }

    @Scheduled(fixedDelay = 30000)
    @SchedulerLock(name = "expireReservationsTask", lockAtLeastFor = "PT10S", lockAtMostFor = "PT25S")
    @Transactional
    public void expirePendingReservations() {
        // O lock distribuído garante que apenas uma instância expire e devolva cada reserva.
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

    private ReservationResponse toRetryResponse(Reservation reservation) {
        if (reservation.getStatus() != ReservationStatus.PENDING) {
            throw new ConflictException(
                    "Cannot retry reservation with status " + reservation.getStatus()
            );
        }
        return toResponse(reservation);
    }
}
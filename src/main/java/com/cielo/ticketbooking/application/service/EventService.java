package com.cielo.ticketbooking.application.service;

import com.cielo.ticketbooking.application.dto.CreateEventRequest;
import com.cielo.ticketbooking.application.dto.EventAvailabilityResponse;
import com.cielo.ticketbooking.domain.exception.ResourceNotFoundException;
import com.cielo.ticketbooking.domain.model.Event;
import com.cielo.ticketbooking.domain.repository.EventRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.util.UUID;

@Service
public class EventService {
    private final EventRepository eventRepository;
    private final StringRedisTemplate redisTemplate;

    public EventService(EventRepository eventRepository, StringRedisTemplate redisTemplate) {
        this.eventRepository = eventRepository;
        this.redisTemplate = redisTemplate;
    }

    @Transactional
    public EventAvailabilityResponse createEvent(CreateEventRequest request) {
        UUID eventId = UUID.randomUUID();
        Event event = Event.builder()
                .id(eventId)
                .title(request.title())
                .totalCapacity(request.totalCapacity())
                .availableCapacity(request.totalCapacity())
                .build();

        // Inicializa a capacidade persistida e o contador usado pelo fluxo de reservas.
        eventRepository.save(event);
        redisTemplate.opsForValue().set("event:" + eventId + ":available", String.valueOf(request.totalCapacity()));
        redisTemplate.opsForValue().set("event:" + eventId + ":total", String.valueOf(request.totalCapacity()));

        return new EventAvailabilityResponse(event.getId(), event.getTitle(), event.getTotalCapacity(), event.getAvailableCapacity());
    }

    public EventAvailabilityResponse getAvailability(UUID eventId) {
        String cacheKey = "event:" + eventId + ":read_cache";
        String cachedValue = redisTemplate.opsForValue().get(cacheKey);

        if (cachedValue != null) {
            // O título é cacheado; a disponibilidade vem do contador atualizado pelo fluxo de reservas.
            String stockKey = "event:" + eventId + ":available";
            String totalCapacityKey = "event:" + eventId + ":total";
            String availableInRedis = redisTemplate.opsForValue().get(stockKey);
            String totalCapacityInRedis = redisTemplate.opsForValue().get(totalCapacityKey);

            if (availableInRedis != null && totalCapacityInRedis != null) {
                return new EventAvailabilityResponse(
                        eventId,
                        cachedValue,
                        Integer.parseInt(totalCapacityInRedis),
                        Integer.parseInt(availableInRedis)
                );
            }

            Event event = eventRepository.findById(eventId)
                    .orElseThrow(() -> new ResourceNotFoundException("Event not found with ID: " + eventId));
            redisTemplate.opsForValue().setIfAbsent(
                    totalCapacityKey,
                    String.valueOf(event.getTotalCapacity())
            );
            redisTemplate.opsForValue().setIfAbsent(
                    stockKey,
                    String.valueOf(event.getAvailableCapacity())
            );
            String warmedStock = redisTemplate.opsForValue().get(stockKey);
            int available = warmedStock != null
                    ? Integer.parseInt(warmedStock)
                    : event.getAvailableCapacity();
            return new EventAvailabilityResponse(
                    eventId,
                    cachedValue,
                    event.getTotalCapacity(),
                    available
            );
        }

        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found with ID: " + eventId));

        // O TTL curto limita a defasagem do título sem armazenar em cache o estoque mutável.
        redisTemplate.opsForValue().set(cacheKey, event.getTitle(), Duration.ofSeconds(5));
        redisTemplate.opsForValue().setIfAbsent(
                "event:" + eventId + ":total",
                String.valueOf(event.getTotalCapacity())
        );
        return new EventAvailabilityResponse(event.getId(), event.getTitle(), event.getTotalCapacity(), event.getAvailableCapacity());
    }
}

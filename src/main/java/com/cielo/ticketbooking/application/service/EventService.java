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

        eventRepository.save(event);
        redisTemplate.opsForValue().set("event:" + eventId + ":available", String.valueOf(request.totalCapacity()));

        return new EventAvailabilityResponse(event.getId(), event.getTitle(), event.getTotalCapacity(), event.getAvailableCapacity());
    }

    public EventAvailabilityResponse getAvailability(UUID eventId) {
        String cacheKey = "event:" + eventId + ":read_cache";
        String cachedValue = redisTemplate.opsForValue().get(cacheKey);

        if (cachedValue != null) {
            String availableInRedis = redisTemplate.opsForValue().get("event:" + eventId + ":available");
            int available = availableInRedis != null ? Integer.parseInt(availableInRedis) : 0;
            return new EventAvailabilityResponse(eventId, cachedValue, null, available);
        }

        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found with ID: " + eventId));

        redisTemplate.opsForValue().set(cacheKey, event.getTitle(), Duration.ofSeconds(3));
        return new EventAvailabilityResponse(event.getId(), event.getTitle(), event.getTotalCapacity(), event.getAvailableCapacity());
    }
}

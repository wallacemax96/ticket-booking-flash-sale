package com.cielo.ticketbooking.application.service;

import com.cielo.ticketbooking.domain.model.Event;
import com.cielo.ticketbooking.domain.repository.EventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventServiceCacheTtlTest {

    @Mock
    private EventRepository eventRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private EventService eventService;

    @BeforeEach
    void setUp() {
        eventService = new EventService(eventRepository, redisTemplate);
    }

    @Test
    void shouldCacheEventTitleForFiveSecondsAfterCacheMiss() {
        UUID eventId = UUID.randomUUID();
        Event event = Event.builder()
                .id(eventId)
                .title("Flash Sale")
                .totalCapacity(20)
                .availableCapacity(20)
                .build();

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("event:" + eventId + ":read_cache")).thenReturn(null);
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));

        eventService.getAvailability(eventId);

        verify(valueOperations).set(
                "event:" + eventId + ":read_cache",
                "Flash Sale",
                Duration.ofSeconds(5)
        );
    }
}

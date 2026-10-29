package com.cielo.ticketbooking.application.service;

import com.cielo.ticketbooking.application.dto.CreateEventRequest;
import com.cielo.ticketbooking.application.dto.EventAvailabilityResponse;
import com.cielo.ticketbooking.domain.exception.ResourceNotFoundException;
import com.cielo.ticketbooking.domain.model.Event;
import com.cielo.ticketbooking.domain.repository.EventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventServiceTest {

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
    @DisplayName("Deve criar evento, persistir e inicializar estoque no Redis")
    void shouldCreateEventAndInitializeRedisStock() {
        CreateEventRequest request = new CreateEventRequest("Show Flash", 100);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(eventRepository.save(any(Event.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        EventAvailabilityResponse response = eventService.createEvent(request);

        ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);
        verify(eventRepository).save(eventCaptor.capture());
        Event savedEvent = eventCaptor.getValue();

        assertThat(savedEvent.getId()).isNotNull();
        assertThat(savedEvent.getTitle()).isEqualTo("Show Flash");
        assertThat(savedEvent.getTotalCapacity()).isEqualTo(100);
        assertThat(savedEvent.getAvailableCapacity()).isEqualTo(100);
        assertThat(response.id()).isEqualTo(savedEvent.getId());
        assertThat(response.title()).isEqualTo("Show Flash");
        assertThat(response.totalCapacity()).isEqualTo(100);
        assertThat(response.availableCapacity()).isEqualTo(100);
        verify(valueOperations).set("event:" + savedEvent.getId() + ":available", "100");
    }

    @Test
    @DisplayName("Deve retornar disponibilidade usando o cache quando o título estiver em cache")
    void shouldReturnAvailabilityFromCache() {
        UUID eventId = UUID.randomUUID();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("event:" + eventId + ":read_cache")).thenReturn("Show Flash");
        when(valueOperations.get("event:" + eventId + ":available")).thenReturn("42");

        EventAvailabilityResponse response = eventService.getAvailability(eventId);

        assertThat(response).isEqualTo(new EventAvailabilityResponse(eventId, "Show Flash", null, 42));
        verify(eventRepository, never()).findById(any());
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("Deve retornar disponibilidade zero quando a chave de estoque não estiver no Redis")
    void shouldReturnZeroAvailabilityWhenStockIsMissingFromCache() {
        UUID eventId = UUID.randomUUID();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("event:" + eventId + ":read_cache")).thenReturn("Show Flash");
        when(valueOperations.get("event:" + eventId + ":available")).thenReturn(null);

        EventAvailabilityResponse response = eventService.getAvailability(eventId);

        assertThat(response).isEqualTo(new EventAvailabilityResponse(eventId, "Show Flash", null, 0));
        verify(eventRepository, never()).findById(any());
    }

    @Test
    @DisplayName("Deve buscar evento no banco e armazenar o título no cache")
    void shouldLoadAvailabilityFromRepositoryAndCacheTitle() {
        UUID eventId = UUID.randomUUID();
        Event event = Event.builder()
                .id(eventId)
                .title("Show Flash")
                .totalCapacity(100)
                .availableCapacity(42)
                .build();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("event:" + eventId + ":read_cache")).thenReturn(null);
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));

        EventAvailabilityResponse response = eventService.getAvailability(eventId);

        assertThat(response).isEqualTo(new EventAvailabilityResponse(eventId, "Show Flash", 100, 42));
        verify(valueOperations).set("event:" + eventId + ":read_cache", "Show Flash", Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("Deve lançar ResourceNotFoundException quando o evento não existir")
    void shouldThrowWhenEventDoesNotExist() {
        UUID eventId = UUID.randomUUID();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("event:" + eventId + ":read_cache")).thenReturn(null);
        when(eventRepository.findById(eventId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> eventService.getAvailability(eventId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Event not found with ID: " + eventId);

        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }
}

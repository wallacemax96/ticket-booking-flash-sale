package com.cielo.ticketbooking.domain.repository;

import com.cielo.ticketbooking.domain.model.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.UUID;

@Repository
public interface EventRepository extends JpaRepository<Event, UUID> {
    @Modifying
    @Query("UPDATE Event e SET e.availableCapacity = e.availableCapacity - :qty WHERE e.id = :id AND e.availableCapacity >= :qty")
    int decrementAvailableCapacity(@Param("id") UUID id, @Param("qty") int qty);

    @Modifying
    @Query("UPDATE Event e SET e.availableCapacity = e.availableCapacity + :qty WHERE e.id = :id")
    void incrementAvailableCapacity(@Param("id") UUID id, @Param("qty") int qty);
}

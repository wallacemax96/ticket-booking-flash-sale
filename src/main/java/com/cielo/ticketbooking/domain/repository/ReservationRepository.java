package com.cielo.ticketbooking.domain.repository;

import com.cielo.ticketbooking.domain.model.Reservation;
import com.cielo.ticketbooking.domain.model.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface ReservationRepository extends JpaRepository<Reservation, UUID> {
    List<Reservation> findByStatusAndExpiresAtBefore(ReservationStatus status, Instant threshold);

    @Modifying
    @Query("""
            UPDATE Reservation r
            SET r.status = :confirmedStatus, r.updatedAt = :updatedAt
            WHERE r.id = :id
              AND r.status = :pendingStatus
              AND r.expiresAt > :updatedAt
            """)
    int confirmPendingReservation(
            @Param("id") UUID id,
            @Param("pendingStatus") ReservationStatus pendingStatus,
            @Param("confirmedStatus") ReservationStatus confirmedStatus,
            @Param("updatedAt") Instant updatedAt
    );

    @Modifying
    @Query("""
            UPDATE Reservation r
            SET r.status = :cancelledStatus, r.updatedAt = :updatedAt
            WHERE r.id = :id
              AND r.status = :pendingStatus
            """)
    int cancelPendingReservation(
            @Param("id") UUID id,
            @Param("pendingStatus") ReservationStatus pendingStatus,
            @Param("cancelledStatus") ReservationStatus cancelledStatus,
            @Param("updatedAt") Instant updatedAt
    );
}

package me.luucka.finance.position;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PositionSnapshotRepository extends JpaRepository<PositionSnapshot, Long> {

    List<PositionSnapshot> findByPositionIdAndUserIdOrderByDateDesc(Long positionId, Long userId);

    Optional<PositionSnapshot> findByPositionIdAndDate(Long positionId, LocalDate date);

    Optional<PositionSnapshot> findByIdAndPositionIdAndUserId(Long id, Long positionId, Long userId);

    List<PositionSnapshot> findByUserId(Long userId);

    /** Most recent snapshot of every position of the user. */
    @Query("""
            select s from PositionSnapshot s
            where s.userId = :userId
              and s.date = (select max(s2.date) from PositionSnapshot s2 where s2.positionId = s.positionId)
            """)
    List<PositionSnapshot> findLatestByUserId(Long userId);

    @Query("select min(s.date) from PositionSnapshot s where s.userId = :userId")
    Optional<LocalDate> findEarliestDate(Long userId);
}

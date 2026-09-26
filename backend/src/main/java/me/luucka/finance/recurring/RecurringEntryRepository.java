package me.luucka.finance.recurring;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RecurringEntryRepository extends JpaRepository<RecurringEntry, Long> {

    List<RecurringEntry> findByUserIdOrderByKindAscStartDateAsc(Long userId);

    Optional<RecurringEntry> findByIdAndUserId(Long id, Long userId);

    boolean existsByCategoryId(Long categoryId);

    @Query("select r.id from RecurringEntry r where r.active = true")
    List<Long> findActiveIds();
}

package me.luucka.finance.cashflow;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface CashEntryRepository extends JpaRepository<CashEntry, Long>, JpaSpecificationExecutor<CashEntry> {

    Optional<CashEntry> findByIdAndUserId(Long id, Long userId);

    boolean existsByCategoryId(Long categoryId);

    boolean existsByRecurringEntryIdAndDate(Long recurringEntryId, LocalDate date);

    List<CashEntry> findByUserIdAndDateBetween(Long userId, LocalDate from, LocalDate to);

    List<CashEntry> findByUserId(Long userId);

    @Query("select distinct year(e.date) from CashEntry e where e.userId = :userId order by year(e.date)")
    List<Integer> findYearsWithEntries(Long userId);
}

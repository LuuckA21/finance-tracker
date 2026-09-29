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

    /**
     * Sums per day, kind and currency: everything a view over all years needs, a few thousand small
     * rows instead of every entry.
     */
    @Query("""
            select new me.luucka.finance.cashflow.DailySum(e.date, e.kind, e.currency, sum(e.amount))
            from CashEntry e where e.userId = :userId group by e.date, e.kind, e.currency""")
    List<DailySum> sumByDay(Long userId);

    /** One row per tag of each of the user's tagged entries. */
    @Query("""
            select new me.luucka.finance.cashflow.TaggedAmount(t, e.date, e.kind, e.categoryId, e.amount, e.currency)
            from CashEntry e join e.tagIds t where e.userId = :userId""")
    List<TaggedAmount> findTaggedAmounts(Long userId);

    @Query("select distinct year(e.date) from CashEntry e where e.userId = :userId order by year(e.date)")
    List<Integer> findYearsWithEntries(Long userId);
}

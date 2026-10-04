package me.luucka.finance.cashflow;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface CashEntryRepository extends JpaRepository<CashEntry, Long>, JpaSpecificationExecutor<CashEntry> {

    Optional<CashEntry> findByIdAndUserId(Long id, Long userId);

    List<CashEntry> findByUserIdAndIdIn(Long userId, Collection<Long> ids);

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

    /** The tags of the user's entries between two dates (both included), one row per entry and tag. */
    @Query("""
            select new me.luucka.finance.cashflow.EntryTag(e.id, t)
            from CashEntry e join e.tagIds t where e.userId = :userId and e.date between :from and :to""")
    List<EntryTag> findEntryTagsBetween(Long userId, LocalDate from, LocalDate to);

    @Query("""
            select new me.luucka.finance.cashflow.DescribedEntry(e.description, e.categoryId, e.kind, e.date)
            from CashEntry e where e.userId = :userId and e.categoryId is not null and e.description is not null""")
    List<DescribedEntry> findDescribedEntries(Long userId);

    @Query("select distinct year(e.date) from CashEntry e where e.userId = :userId order by year(e.date)")
    List<Integer> findYearsWithEntries(Long userId);
}

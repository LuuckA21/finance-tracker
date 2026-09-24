package me.luucka.finance.user;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface RecoveryCodeRepository extends JpaRepository<RecoveryCode, Long> {

    Optional<RecoveryCode> findFirstByUserIdAndCodeHashAndUsedAtIsNull(Long userId, String codeHash);

    long countByUserIdAndUsedAtIsNull(Long userId);

    /** @return 1 when the code was unused and is now consumed, 0 otherwise */
    @Transactional
    @Modifying
    @Query("update RecoveryCode r set r.usedAt = :now where r.id = :id and r.usedAt is null")
    int markUsed(Long id, Instant now);

    @Transactional
    @Modifying
    @Query("delete from RecoveryCode r where r.userId = :userId")
    void deleteAllForUser(Long userId);
}

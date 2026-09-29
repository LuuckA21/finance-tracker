package me.luucka.finance.notification;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface NotificationSettingsRepository extends JpaRepository<NotificationSettings, Long> {

    /** Users with a confirmed address whose account is enabled. */
    @Query("""
            select s.userId from NotificationSettings s, me.luucka.finance.user.AppUser u
            where u.id = s.userId and u.enabled = true and s.email is not null""")
    List<Long> findUserIdsWithEmail();

    /** Creates the user's row with the defaults unless it exists (safe when two requests race). */
    @Modifying
    @Query(value = "insert into notification_settings (user_id) values (:userId) on conflict do nothing",
            nativeQuery = true)
    void createIfMissing(long userId);

    /**
     * The row locked until the transaction ends ({@code select ... for update}): parallel requests of
     * one user are handled one after the other, so attempt counters and waiting times cannot be skipped.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from NotificationSettings s where s.userId = :userId")
    Optional<NotificationSettings> findForUpdate(long userId);
}

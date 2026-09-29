package me.luucka.finance.notification;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface NotificationSettingsRepository extends JpaRepository<NotificationSettings, Long> {

    /** Users with a confirmed address whose account is enabled. */
    @Query("""
            select s.userId from NotificationSettings s, me.luucka.finance.user.AppUser u
            where u.id = s.userId and u.enabled = true and s.email is not null""")
    List<Long> findUserIdsWithEmail();
}

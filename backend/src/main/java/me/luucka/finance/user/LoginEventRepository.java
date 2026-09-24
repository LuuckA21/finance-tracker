package me.luucka.finance.user;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LoginEventRepository extends JpaRepository<LoginEvent, Long> {

    List<LoginEvent> findTop20ByUserIdOrderByCreatedAtDesc(Long userId);
}

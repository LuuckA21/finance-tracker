package me.luucka.finance.goal;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SavingsGoalRepository extends JpaRepository<SavingsGoal, Long> {

    List<SavingsGoal> findByUserIdOrderByNameAsc(Long userId);

    Optional<SavingsGoal> findByIdAndUserId(Long id, Long userId);

    long countByUserId(Long userId);
}

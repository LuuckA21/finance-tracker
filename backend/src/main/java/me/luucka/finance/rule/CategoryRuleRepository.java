package me.luucka.finance.rule;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRuleRepository extends JpaRepository<CategoryRule, Long> {

    List<CategoryRule> findByUserIdOrderByPatternAsc(Long userId);

    Optional<CategoryRule> findByIdAndUserId(Long id, Long userId);
}

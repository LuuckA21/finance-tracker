package me.luucka.finance.forecast;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ForecastScenarioRepository extends JpaRepository<ForecastScenario, Long> {

    List<ForecastScenario> findByUserIdOrderByYearAscNameAsc(Long userId);

    Optional<ForecastScenario> findByIdAndUserId(Long id, Long userId);

    long countByUserId(Long userId);
}

package me.luucka.finance.fx;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, Long> {

    List<ExchangeRate> findByUserIdAndBaseCurrencyOrderByCurrencyAscDateDesc(Long userId, String baseCurrency);

    Optional<ExchangeRate> findByUserIdAndBaseCurrencyAndCurrencyAndDate(Long userId, String baseCurrency,
                                                                         String currency, LocalDate date);

    Optional<ExchangeRate> findByIdAndUserId(Long id, Long userId);
}

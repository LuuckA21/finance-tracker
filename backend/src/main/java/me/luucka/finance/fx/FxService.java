package me.luucka.finance.fx;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import me.luucka.finance.common.ApiException;
import me.luucka.finance.core.Currencies;
import me.luucka.finance.core.Money;
import me.luucka.finance.core.fx.FxTable;
import me.luucka.finance.user.AppUser;
import me.luucka.finance.user.AppUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FxService {

    public record RateResponse(long id, String baseCurrency, String currency, LocalDate date, BigDecimal rate) {
        static RateResponse of(ExchangeRate r) {
            return new RateResponse(r.getId(), r.getBaseCurrency(), r.getCurrency(), r.getDate(),
                    Money.plain(r.getRate()));
        }
    }

    private final ExchangeRateRepository rates;
    private final AppUserRepository users;

    public FxService(ExchangeRateRepository rates, AppUserRepository users) {
        this.rates = rates;
        this.users = users;
    }

    public String baseCurrency(long userId) {
        return users.findById(userId).map(AppUser::getBaseCurrency).orElseThrow(() -> ApiException.notFound("User"));
    }

    /** Builds the conversion table for the user's current base currency. */
    @Transactional(readOnly = true)
    public FxTable table(long userId) {
        String base = baseCurrency(userId);
        FxTable table = new FxTable(base);
        for (ExchangeRate rate : rates.findByUserIdAndBaseCurrencyOrderByCurrencyAscDateDesc(userId, base)) {
            table.put(rate.getCurrency(), rate.getDate(), rate.getRate());
        }
        return table;
    }

    @Transactional(readOnly = true)
    public List<RateResponse> list(long userId) {
        return rates.findByUserIdAndBaseCurrencyOrderByCurrencyAscDateDesc(userId, baseCurrency(userId)).stream()
                .map(RateResponse::of)
                .toList();
    }

    /** Creates the rate for (currency, date) or overwrites the existing one. */
    @Transactional
    public RateResponse upsert(long userId, String currency, LocalDate date, BigDecimal rate) {
        String base = baseCurrency(userId);
        String normalized = Currencies.normalize(currency);
        if (normalized.equals(base)) {
            throw ApiException.badRequest("base_currency_rate",
                    "No rate is needed for the base currency (" + base + ")");
        }
        ExchangeRate entity = rates.findByUserIdAndBaseCurrencyAndCurrencyAndDate(userId, base, normalized, date)
                .orElseGet(() -> new ExchangeRate(userId, base, normalized, date));
        entity.setRate(rate);
        return RateResponse.of(rates.save(entity));
    }

    @Transactional
    public void delete(long userId, long id) {
        ExchangeRate rate = rates.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Rate"));
        rates.delete(rate);
    }
}

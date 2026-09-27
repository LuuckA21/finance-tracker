package me.luucka.finance.fx;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import me.luucka.finance.common.ApiException;
import me.luucka.finance.core.Currencies;
import me.luucka.finance.core.Money;
import me.luucka.finance.core.fx.CentralRates;
import me.luucka.finance.core.fx.FxTable;
import me.luucka.finance.user.AppUser;
import me.luucka.finance.user.AppUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FxService {

    /** Significant digits of derived ECB rates shown to users (the calculations keep full precision). */
    private static final MathContext RATE_DIGITS = new MathContext(8);

    public record RateResponse(long id, String baseCurrency, String currency, LocalDate date, BigDecimal rate) {
        static RateResponse of(ExchangeRate r) {
            return new RateResponse(r.getId(), r.getBaseCurrency(), r.getCurrency(), r.getDate(),
                    Money.plain(r.getRate()));
        }
    }

    /**
     * An ECB rate towards the user's base currency ({@code 1 currency = rate base}) and, when the
     * user has one in effect today, the manual rate used instead.
     */
    public record CentralRateResponse(String currency, BigDecimal rate, LocalDate date, BigDecimal manualRate) {
    }

    public record CentralRatesResponse(String source, String baseCurrency, LocalDate latestDate,
                                       List<CentralRateResponse> rates) {
    }

    private final ExchangeRateRepository rates;
    private final AppUserRepository users;
    private final CentralRateService central;
    private final Clock clock;

    public FxService(ExchangeRateRepository rates, AppUserRepository users, CentralRateService central,
                     Clock clock) {
        this.rates = rates;
        this.users = users;
        this.central = central;
        this.clock = clock;
    }

    public String baseCurrency(long userId) {
        return users.findById(userId).map(AppUser::getBaseCurrency).orElseThrow(() -> ApiException.notFound("User"));
    }

    /** Builds the conversion table for the user's current base currency: manual rates first, then ECB. */
    @Transactional(readOnly = true)
    public FxTable table(long userId) {
        return table(userId, baseCurrency(userId));
    }

    private FxTable table(long userId, String base) {
        FxTable table = new FxTable(base, central.rates());
        for (ExchangeRate rate : rates.findByUserIdAndBaseCurrencyOrderByCurrencyAscDateDesc(userId, base)) {
            table.put(rate.getCurrency(), rate.getDate(), rate.getRate());
        }
        return table;
    }

    /** Today's ECB rates towards the user's base currency, marking those replaced by a manual rate. */
    @Transactional(readOnly = true)
    public CentralRatesResponse centralRates(long userId) {
        String base = baseCurrency(userId);
        CentralRates ecb = central.rates();
        FxTable table = table(userId, base);
        LocalDate today = LocalDate.now(clock);
        List<CentralRateResponse> result = new ArrayList<>();
        for (String currency : ecb.currencies()) {
            if (currency.equals(base)) {
                continue;
            }
            ecb.onOrBefore(currency, base, today).ifPresent(quote -> {
                BigDecimal manual = table.quote(currency, today)
                        .filter(q -> q.source() == FxTable.Source.MANUAL)
                        .map(q -> Money.plain(q.rate()))
                        .orElse(null);
                result.add(new CentralRateResponse(currency, Money.plain(quote.rate().round(RATE_DIGITS)),
                        quote.date(), manual));
            });
        }
        return new CentralRatesResponse("ECB", base, ecb.latestDate().orElse(null), result);
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

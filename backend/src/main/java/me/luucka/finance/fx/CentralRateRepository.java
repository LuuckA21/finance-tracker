package me.luucka.finance.fx;

import java.sql.Date;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import me.luucka.finance.core.fx.CentralRates;
import me.luucka.finance.core.fx.EcbXmlParser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** ECB reference rates in {@code central_exchange_rate}; plain JDBC for bulk loads. */
@Repository
public class CentralRateRepository {

    static final String ANCHOR = "EUR";

    private final JdbcTemplate jdbc;

    public CentralRateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<LocalDate> latestDate() {
        return Optional.ofNullable(jdbc.queryForObject("SELECT max(rate_date) FROM central_exchange_rate", Date.class))
                .map(Date::toLocalDate);
    }

    /**
     * Inserts new rates and applies corrections to existing ones.
     *
     * @return rows inserted or changed
     */
    @Transactional
    public int upsert(List<EcbXmlParser.Rate> rates) {
        int[][] counts = jdbc.batchUpdate("""
                INSERT INTO central_exchange_rate (currency, rate_date, rate) VALUES (?, ?, ?)
                ON CONFLICT (currency, rate_date) DO UPDATE SET rate = EXCLUDED.rate, fetched_at = now()
                WHERE central_exchange_rate.rate <> EXCLUDED.rate""", rates, 1000, (ps, rate) -> {
            ps.setString(1, rate.currency());
            ps.setObject(2, rate.date());
            ps.setBigDecimal(3, rate.rate());
        });
        return Arrays.stream(counts).flatMapToInt(Arrays::stream).map(c -> Math.max(c, 0)).sum();
    }

    @Transactional(readOnly = true)
    public CentralRates loadAll() {
        CentralRates.Builder builder = CentralRates.builder(ANCHOR);
        jdbc.query("SELECT currency, rate_date, rate FROM central_exchange_rate", rs -> {
            builder.put(rs.getString(1), rs.getObject(2, LocalDate.class), rs.getBigDecimal(3));
        });
        return builder.build();
    }
}

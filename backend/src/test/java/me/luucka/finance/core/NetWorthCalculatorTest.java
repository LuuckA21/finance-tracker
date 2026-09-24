package me.luucka.finance.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import me.luucka.finance.core.fx.FxTable;
import me.luucka.finance.core.valuation.NetWorthCalculator;
import me.luucka.finance.core.valuation.PositionHistory;
import me.luucka.finance.core.valuation.ValuationDates;
import me.luucka.finance.core.valuation.ValuationSnapshot;
import org.junit.jupiter.api.Test;

class NetWorthCalculatorTest {

    private final FxTable fx = new FxTable("CHF")
            .put("USD", LocalDate.of(2026, 1, 1), new BigDecimal("0.90"))
            .put("USD", LocalDate.of(2026, 3, 1), new BigDecimal("0.80"));

    private final PositionHistory bank = new PositionHistory(1, AssetClass.CASH, "CHF", List.of(
            snap(2026, 1, 31, "10000", "1"),
            snap(2026, 2, 28, "12000", "1")));

    private final PositionHistory btc = new PositionHistory(2, AssetClass.CRYPTO, "USD", List.of(
            snap(2026, 2, 15, "0.5", "60000"),
            snap(2026, 4, 10, "0", "70000")));

    private final PositionHistory etf = new PositionHistory(3, AssetClass.ETF, "JPY", List.of(
            snap(2026, 1, 1, "10", "30000")));

    private final List<PositionHistory> all = List.of(bank, btc, etf);

    @Test
    void valuesPositionsWithCarryForwardAndFx() {
        var detail = NetWorthCalculator.valueAt(all, fx, LocalDate.of(2026, 3, 31));
        var point = detail.point();

        // bank 12000 CHF + BTC 0.5 * 60000 USD * 0.80 = 24000 CHF; JPY unconvertible
        assertEquals(new BigDecimal("36000.00"), point.total());
        assertEquals(new BigDecimal("12000.00"), point.byClass().get(AssetClass.CASH));
        assertEquals(new BigDecimal("24000.00"), point.byClass().get(AssetClass.CRYPTO));
        assertTrue(point.unconvertedCurrencies().contains("JPY"));

        assertEquals(3, detail.positions().size());
        assertEquals(2L, detail.positions().get(0).positionId());
        var jpy = detail.positions().get(2);
        assertEquals(new BigDecimal("300000.00"), jpy.valueLocal());
        assertNull(jpy.valueBase());
    }

    @Test
    void positionsDoNotExistBeforeFirstSnapshot() {
        var point = NetWorthCalculator.valueAt(List.of(bank, btc), fx, LocalDate.of(2026, 1, 31)).point();
        assertEquals(new BigDecimal("10000.00"), point.total());
        assertEquals(null, point.byClass().get(AssetClass.CRYPTO));
    }

    @Test
    void zeroQuantityClosesPosition() {
        var point = NetWorthCalculator.valueAt(List.of(btc), fx, LocalDate.of(2026, 5, 1)).point();
        assertEquals(new BigDecimal("0.00"), point.total());
    }

    @Test
    void monthlySeries() {
        LocalDate today = LocalDate.of(2026, 3, 15);
        List<LocalDate> dates = ValuationDates.monthEnds(YearMonth.of(2026, 1), YearMonth.of(2026, 6), today);
        assertEquals(List.of(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28), today), dates);

        var series = NetWorthCalculator.series(List.of(bank, btc), fx, dates);
        assertEquals(new BigDecimal("10000.00"), series.get(0).total());
        assertEquals(new BigDecimal("39000.00"), series.get(1).total()); // 12000 + 30000 * 0.90
        assertEquals(new BigDecimal("36000.00"), series.get(2).total()); // 12000 + 30000 * 0.80
    }

    @Test
    void yearlyDates() {
        LocalDate today = LocalDate.of(2026, 9, 24);
        assertEquals(List.of(LocalDate.of(2024, 12, 31), LocalDate.of(2025, 12, 31), today),
                ValuationDates.yearEnds(2024, 2030, today));
        assertThrows(IllegalArgumentException.class, () -> ValuationDates.yearEnds(2027, 2026, today));
    }

    private static ValuationSnapshot snap(int y, int m, int d, String qty, String price) {
        return new ValuationSnapshot(LocalDate.of(y, m, d), new BigDecimal(qty), new BigDecimal(price));
    }
}

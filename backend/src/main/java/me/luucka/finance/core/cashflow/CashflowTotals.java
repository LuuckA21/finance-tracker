package me.luucka.finance.core.cashflow;

import java.math.BigDecimal;
import java.math.RoundingMode;

import me.luucka.finance.core.Money;

/**
 * Income, expenses and net result for a period, in the base currency, rounded for output.
 *
 * @param income      total income
 * @param expense     total expenses (positive number)
 * @param net         income minus expenses
 * @param savingsRate net divided by income as a percentage, or {@code null} when there is no income
 * @param transferred moved between the user's own accounts/investments: not part of income,
 *                    expenses, net or savings rate
 */
public record CashflowTotals(BigDecimal income, BigDecimal expense, BigDecimal net, BigDecimal savingsRate,
                             BigDecimal transferred) {

    public static final CashflowTotals ZERO = of(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

    public static CashflowTotals of(BigDecimal income, BigDecimal expense, BigDecimal transferred) {
        BigDecimal net = income.subtract(expense);
        BigDecimal rate = income.signum() > 0
                ? net.multiply(BigDecimal.valueOf(100)).divide(income, 1, RoundingMode.HALF_EVEN)
                : null;
        return new CashflowTotals(Money.round(income), Money.round(expense), Money.round(net), rate,
                Money.round(transferred));
    }
}

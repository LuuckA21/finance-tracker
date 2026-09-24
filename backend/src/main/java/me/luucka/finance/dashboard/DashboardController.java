package me.luucka.finance.dashboard;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;

import me.luucka.finance.auth.AppPrincipal;
import me.luucka.finance.common.ApiException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    /** Monthly income/expenses and category breakdown of one year. */
    @GetMapping("/cashflow")
    public DashboardService.CashflowYearResponse cashflow(@AuthenticationPrincipal AppPrincipal me,
                                                          @RequestParam int year) {
        if (year < 1900 || year > 2200) {
            throw ApiException.badRequest("invalid_year", "Year out of range");
        }
        return service.cashflowYear(me.id(), year);
    }

    /** Yearly totals across all years. */
    @GetMapping("/cashflow/years")
    public DashboardService.CashflowYearsResponse cashflowYears(@AuthenticationPrincipal AppPrincipal me) {
        return service.cashflowYears(me.id());
    }

    /**
     * Net worth over time.
     *
     * @param from first month ({@code yyyy-MM}); defaults to the month of the first snapshot
     * @param to   last month ({@code yyyy-MM}); defaults to the current month
     */
    @GetMapping("/net-worth")
    public DashboardService.NetWorthSeriesResponse netWorth(
            @AuthenticationPrincipal AppPrincipal me,
            @RequestParam(defaultValue = "MONTH") DashboardService.Granularity granularity,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return service.netWorthSeries(me.id(), granularity, parseMonth(from), parseMonth(to));
    }

    /** Value of every position on a date (default: today). */
    @GetMapping("/net-worth/detail")
    public DashboardService.NetWorthDetailResponse netWorthDetail(
            @AuthenticationPrincipal AppPrincipal me,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.netWorthAt(me.id(), date);
    }

    private static YearMonth parseMonth(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return YearMonth.parse(value);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("invalid_month", "Expected a month in the form yyyy-MM");
        }
    }
}

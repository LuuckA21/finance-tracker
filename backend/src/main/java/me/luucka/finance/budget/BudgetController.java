package me.luucka.finance.budget;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import me.luucka.finance.auth.AppPrincipal;
import me.luucka.finance.common.ApiException;
import me.luucka.finance.common.CurrencyCode;
import me.luucka.finance.core.budget.BudgetCalculator;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Budgets per expense category (monthly, quarterly or yearly) and how spending compares with them. */
@RestController
@RequestMapping("/api/budgets")
public class BudgetController {

    public record BudgetRequest(
            @NotNull @DecimalMin(value = "0.01") @DecimalMax("999999999999999")
            @Digits(integer = 15, fraction = 4) BigDecimal amount,
            @NotNull @CurrencyCode String currency,
            /** Default {@code MONTHLY} */
            BudgetCalculator.Period period) {
    }

    private static final YearMonth MIN_MONTH = YearMonth.of(1900, 1);
    private static final YearMonth MAX_MONTH = YearMonth.of(2199, 12);

    private final BudgetService service;

    public BudgetController(BudgetService service) {
        this.service = service;
    }

    @GetMapping
    public List<BudgetService.BudgetResponse> list(@AuthenticationPrincipal AppPrincipal me) {
        return service.list(me.id());
    }

    /** {@code month} as {@code yyyy-MM}, default the current month. */
    @GetMapping("/status")
    public BudgetService.StatusResponse status(@AuthenticationPrincipal AppPrincipal me,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        if (month != null && (month.isBefore(MIN_MONTH) || month.isAfter(MAX_MONTH))) {
            throw ApiException.badRequest("month_out_of_range", "The month must be between 1900-01 and 2199-12");
        }
        return service.status(me.id(), month);
    }

    @PutMapping("/{categoryId}")
    public BudgetService.BudgetResponse save(@AuthenticationPrincipal AppPrincipal me, @PathVariable long categoryId,
                                             @Valid @RequestBody BudgetRequest body) {
        return service.save(me.id(), categoryId, body.amount(), body.currency(),
                body.period() == null ? BudgetCalculator.Period.MONTHLY : body.period());
    }

    @DeleteMapping("/{categoryId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AppPrincipal me, @PathVariable long categoryId) {
        service.delete(me.id(), categoryId);
        return ResponseEntity.noContent().build();
    }
}

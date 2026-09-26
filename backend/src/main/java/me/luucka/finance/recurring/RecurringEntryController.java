package me.luucka.finance.recurring;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import me.luucka.finance.auth.AppPrincipal;
import me.luucka.finance.common.CurrencyCode;
import me.luucka.finance.core.EntryKind;
import me.luucka.finance.core.recurrence.Frequency;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/recurring-entries")
public class RecurringEntryController {

    public record RuleRequest(
            @NotNull EntryKind kind,
            @NotNull Long categoryId,
            @NotNull @DecimalMin(value = "0.0001") @DecimalMax("999999999999999")
            @Digits(integer = 15, fraction = 4) BigDecimal amount,
            @NotNull @CurrencyCode String currency,
            @Size(max = 500) String description,
            @NotNull Frequency frequency,
            @NotNull LocalDate startDate,
            LocalDate endDate,
            // Omitted means active
            Boolean active) {

        RecurringEntryService.RuleData toData() {
            return new RecurringEntryService.RuleData(kind, categoryId, amount, currency, description, frequency,
                    startDate, endDate, !Boolean.FALSE.equals(active));
        }
    }

    private final RecurringEntryService service;

    public RecurringEntryController(RecurringEntryService service) {
        this.service = service;
    }

    @GetMapping
    public List<RecurringEntryService.RuleResponse> list(@AuthenticationPrincipal AppPrincipal me) {
        return service.list(me.id());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RecurringEntryService.RuleResponse create(@AuthenticationPrincipal AppPrincipal me,
                                                     @Valid @RequestBody RuleRequest body) {
        return service.create(me.id(), body.toData());
    }

    @PutMapping("/{id}")
    public RecurringEntryService.RuleResponse update(@AuthenticationPrincipal AppPrincipal me, @PathVariable long id,
                                                     @Valid @RequestBody RuleRequest body) {
        return service.update(me.id(), id, body.toData());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AppPrincipal me, @PathVariable long id) {
        service.delete(me.id(), id);
        return ResponseEntity.noContent().build();
    }
}

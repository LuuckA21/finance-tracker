package me.luucka.finance.cashflow;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import me.luucka.finance.auth.AppPrincipal;
import me.luucka.finance.common.CurrencyCode;
import me.luucka.finance.common.PageResponse;
import me.luucka.finance.core.EntryKind;
import org.springframework.format.annotation.DateTimeFormat;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cash-entries")
public class CashEntryController {

    public record EntryRequest(
            @NotNull LocalDate date,
            @NotNull EntryKind kind,
            @NotNull Long categoryId,
            @NotNull @DecimalMin(value = "0.0001") @DecimalMax("999999999999999")
            @Digits(integer = 15, fraction = 4) BigDecimal amount,
            @NotNull @CurrencyCode String currency,
            @Size(max = 500) String description) {

        CashEntryService.EntryData toData() {
            return new CashEntryService.EntryData(date, kind, categoryId, amount, currency, description);
        }
    }

    private final CashEntryService service;

    public CashEntryController(CashEntryService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<CashEntryService.EntryResponse> list(
            @AuthenticationPrincipal AppPrincipal me,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) EntryKind kind,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        var filter = new CashEntryService.Filter(from, to, kind, categoryId, q);
        return service.list(me.id(), filter, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CashEntryService.EntryResponse create(@AuthenticationPrincipal AppPrincipal me,
                                                 @Valid @RequestBody EntryRequest body) {
        return service.create(me.id(), body.toData());
    }

    @PutMapping("/{id}")
    public CashEntryService.EntryResponse update(@AuthenticationPrincipal AppPrincipal me, @PathVariable long id,
                                                 @Valid @RequestBody EntryRequest body) {
        return service.update(me.id(), id, body.toData());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AppPrincipal me, @PathVariable long id) {
        service.delete(me.id(), id);
        return ResponseEntity.noContent().build();
    }
}

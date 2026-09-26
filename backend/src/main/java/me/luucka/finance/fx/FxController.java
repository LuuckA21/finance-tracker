package me.luucka.finance.fx;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import me.luucka.finance.auth.AppPrincipal;
import me.luucka.finance.common.CurrencyCode;
import me.luucka.finance.common.ReasonableDate;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/fx-rates")
public class FxController {

    public record RateRequest(
            @NotNull @CurrencyCode String currency,
            @NotNull @ReasonableDate LocalDate date,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 12) BigDecimal rate) {
    }

    private final FxService service;

    public FxController(FxService service) {
        this.service = service;
    }

    @GetMapping
    public List<FxService.RateResponse> list(@AuthenticationPrincipal AppPrincipal me) {
        return service.list(me.id());
    }

    @GetMapping("/central")
    public FxService.CentralRatesResponse central(@AuthenticationPrincipal AppPrincipal me) {
        return service.centralRates(me.id());
    }

    @PostMapping
    public FxService.RateResponse upsert(@AuthenticationPrincipal AppPrincipal me, @Valid @RequestBody RateRequest body) {
        return service.upsert(me.id(), body.currency(), body.date(), body.rate());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AppPrincipal me, @PathVariable long id) {
        service.delete(me.id(), id);
        return ResponseEntity.noContent().build();
    }
}

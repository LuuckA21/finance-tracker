package me.luucka.finance.goal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import me.luucka.finance.auth.AppPrincipal;
import me.luucka.finance.common.CurrencyCode;
import me.luucka.finance.common.ReasonableDate;
import me.luucka.finance.core.goal.GoalCalculator;
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

/** Savings goals: a balance to reach on some positions, or a yearly amount to put into them. */
@RestController
@RequestMapping("/api/goals")
public class SavingsGoalController {

    public record GoalRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull GoalCalculator.Kind kind,
            @NotNull @DecimalMin(value = "0.01") @DecimalMax("999999999999999")
            @Digits(integer = 15, fraction = 4) BigDecimal targetAmount,
            @NotNull @CurrencyCode String currency,
            @ReasonableDate LocalDate targetDate,
            @NotEmpty @Size(max = 50) Set<@NotNull Long> positionIds) {

        SavingsGoalService.GoalData data() {
            return new SavingsGoalService.GoalData(name, kind, targetAmount, currency, targetDate, positionIds);
        }
    }

    private final SavingsGoalService service;

    public SavingsGoalController(SavingsGoalService service) {
        this.service = service;
    }

    @GetMapping
    public List<SavingsGoalService.GoalResponse> list(@AuthenticationPrincipal AppPrincipal me) {
        return service.list(me.id());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SavingsGoalService.GoalResponse create(@AuthenticationPrincipal AppPrincipal me,
                                                  @Valid @RequestBody GoalRequest body) {
        return service.create(me.id(), body.data());
    }

    @PutMapping("/{id}")
    public SavingsGoalService.GoalResponse update(@AuthenticationPrincipal AppPrincipal me, @PathVariable long id,
                                                  @Valid @RequestBody GoalRequest body) {
        return service.update(me.id(), id, body.data());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AppPrincipal me, @PathVariable long id) {
        service.delete(me.id(), id);
        return ResponseEntity.noContent().build();
    }
}

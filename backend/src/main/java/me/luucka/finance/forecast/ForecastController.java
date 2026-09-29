package me.luucka.finance.forecast;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import me.luucka.finance.auth.AppPrincipal;
import me.luucka.finance.core.EntryKind;
import me.luucka.finance.core.forecast.ForecastCalculator;
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

/** Forecast scenarios of income and expenses for a coming year, and their result. */
@RestController
@RequestMapping("/api/forecasts")
public class ForecastController {

    /**
     * @param name required to save, not to preview
     */
    public record ScenarioRequest(
            @Size(max = 100) String name,
            @NotNull @Min(2000) @Max(2200) Integer year,
            @NotNull @DecimalMin("-100") @DecimalMax("1000") @Digits(integer = 4, fraction = 2) BigDecimal incomeGrowth,
            @NotNull @DecimalMin("-100") @DecimalMax("1000") @Digits(integer = 4, fraction = 2) BigDecimal expenseGrowth,
            @Size(max = 200) Set<@NotNull Long> excludedTagIds,
            @Size(max = 200) Set<@NotNull Long> excludedCategoryIds,
            @Size(max = 100) List<@NotNull @Valid ItemRequest> items) {

        ForecastService.ScenarioData data() {
            return new ForecastService.ScenarioData(name, year, incomeGrowth, expenseGrowth,
                    excludedTagIds == null ? Set.of() : excludedTagIds,
                    excludedCategoryIds == null ? Set.of() : excludedCategoryIds,
                    items == null ? List.of() : items.stream().map(ItemRequest::data).toList());
        }
    }

    /**
     * @param amount   in the base currency; negative to take something away
     * @param endMonth for {@code MONTHLY}: the last month, December when missing
     */
    public record ItemRequest(
            @NotBlank @Size(max = 100) String description,
            @NotNull EntryKind kind,
            Long categoryId,
            @NotNull @DecimalMin("-999999999999999") @DecimalMax("999999999999999")
            @Digits(integer = 15, fraction = 4) BigDecimal amount,
            @NotNull ForecastCalculator.Schedule schedule,
            @NotNull @Min(1) @Max(12) Integer startMonth,
            @Min(1) @Max(12) Integer endMonth) {

        ForecastService.ItemData data() {
            return new ForecastService.ItemData(description, kind, categoryId, amount, schedule, startMonth, endMonth);
        }
    }

    private final ForecastService service;

    public ForecastController(ForecastService service) {
        this.service = service;
    }

    @GetMapping
    public List<ForecastService.ScenarioResponse> list(@AuthenticationPrincipal AppPrincipal me) {
        return service.list(me.id());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ForecastService.ScenarioResponse create(@AuthenticationPrincipal AppPrincipal me,
                                                   @Valid @RequestBody ScenarioRequest body) {
        return service.create(me.id(), body.data());
    }

    @PutMapping("/{id}")
    public ForecastService.ScenarioResponse update(@AuthenticationPrincipal AppPrincipal me, @PathVariable long id,
                                                   @Valid @RequestBody ScenarioRequest body) {
        return service.update(me.id(), id, body.data());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AppPrincipal me, @PathVariable long id) {
        service.delete(me.id(), id);
        return ResponseEntity.noContent().build();
    }

    /** The forecast of the scenario in the body, saved or not: the page shows it while editing. */
    @PostMapping("/preview")
    public ForecastService.ForecastResponse preview(@AuthenticationPrincipal AppPrincipal me,
                                                    @Valid @RequestBody ScenarioRequest body) {
        return service.preview(me.id(), body.data());
    }
}

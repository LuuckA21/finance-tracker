package me.luucka.finance.position;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import me.luucka.finance.auth.AppPrincipal;
import me.luucka.finance.common.CurrencyCode;
import me.luucka.finance.core.AssetClass;
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
@RequestMapping("/api/positions")
public class PositionController {

    public record PositionRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 32) String symbol,
            @NotNull AssetClass assetClass,
            @NotNull @CurrencyCode String currency,
            @Size(max = 1000) String notes,
            boolean archived) {

        PositionService.PositionData toData() {
            return new PositionService.PositionData(name, symbol, assetClass, currency, notes, archived);
        }
    }

    public record SnapshotRequest(
            @NotNull LocalDate date,
            @NotNull @DecimalMin("0") @Digits(integer = 26, fraction = 12) BigDecimal quantity,
            @NotNull @DecimalMin("0") @Digits(integer = 26, fraction = 12) BigDecimal unitPrice,
            @Size(max = 500) String note) {

        PositionService.SnapshotData toData() {
            return new PositionService.SnapshotData(date, quantity, unitPrice, note);
        }
    }

    public record BulkItemRequest(
            @NotNull Long positionId,
            @NotNull @DecimalMin("0") @Digits(integer = 26, fraction = 12) BigDecimal quantity,
            @NotNull @DecimalMin("0") @Digits(integer = 26, fraction = 12) BigDecimal unitPrice) {
    }

    public record BulkSnapshotRequest(
            @NotNull LocalDate date,
            @NotEmpty @Size(max = 500) List<@Valid BulkItemRequest> items) {
    }

    private final PositionService service;

    public PositionController(PositionService service) {
        this.service = service;
    }

    @GetMapping
    public List<PositionService.PositionResponse> list(@AuthenticationPrincipal AppPrincipal me) {
        return service.list(me.id());
    }

    @GetMapping("/{id}")
    public PositionService.PositionResponse get(@AuthenticationPrincipal AppPrincipal me, @PathVariable long id) {
        return service.get(me.id(), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PositionService.PositionResponse create(@AuthenticationPrincipal AppPrincipal me,
                                                   @Valid @RequestBody PositionRequest body) {
        return service.create(me.id(), body.toData());
    }

    @PutMapping("/{id}")
    public PositionService.PositionResponse update(@AuthenticationPrincipal AppPrincipal me, @PathVariable long id,
                                                   @Valid @RequestBody PositionRequest body) {
        return service.update(me.id(), id, body.toData());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AppPrincipal me, @PathVariable long id) {
        service.delete(me.id(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/snapshots")
    public List<PositionService.SnapshotResponse> snapshots(@AuthenticationPrincipal AppPrincipal me,
                                                            @PathVariable long id) {
        return service.snapshots(me.id(), id);
    }

    @PostMapping("/{id}/snapshots")
    public PositionService.SnapshotResponse upsertSnapshot(@AuthenticationPrincipal AppPrincipal me,
                                                           @PathVariable long id,
                                                           @Valid @RequestBody SnapshotRequest body) {
        return service.upsertSnapshot(me.id(), id, body.toData());
    }

    @PutMapping("/{id}/snapshots/{snapshotId}")
    public PositionService.SnapshotResponse updateSnapshot(@AuthenticationPrincipal AppPrincipal me,
                                                           @PathVariable long id, @PathVariable long snapshotId,
                                                           @Valid @RequestBody SnapshotRequest body) {
        return service.updateSnapshot(me.id(), id, snapshotId, body.toData());
    }

    @DeleteMapping("/{id}/snapshots/{snapshotId}")
    public ResponseEntity<Void> deleteSnapshot(@AuthenticationPrincipal AppPrincipal me, @PathVariable long id,
                                               @PathVariable long snapshotId) {
        service.deleteSnapshot(me.id(), id, snapshotId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/snapshots/bulk")
    public Map<String, Integer> bulkSnapshot(@AuthenticationPrincipal AppPrincipal me,
                                             @Valid @RequestBody BulkSnapshotRequest body) {
        var items = body.items().stream()
                .map(i -> new PositionService.BulkItem(i.positionId(), i.quantity(), i.unitPrice()))
                .toList();
        return Map.of("saved", service.bulkSnapshot(me.id(), body.date(), items));
    }
}

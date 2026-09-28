package me.luucka.finance.cashflow;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import me.luucka.finance.auth.AppPrincipal;
import me.luucka.finance.common.CurrencyCode;
import me.luucka.finance.common.ReasonableDate;
import me.luucka.finance.common.PageResponse;
import me.luucka.finance.core.EntryKind;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/cash-entries")
public class CashEntryController {

    public record EntryRequest(
            @NotNull @ReasonableDate LocalDate date,
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

    /** Rows confirmed by the user after an import preview; validated like single entries. */
    public record ImportRequest(
            @NotEmpty @Size(max = CashEntryService.MAX_IMPORT_ROWS) List<@NotNull @Valid EntryRequest> entries) {
    }

    public record ImportResponse(int imported) {
    }

    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final CashEntryService service;
    private final CashEntryCsvService csv;
    private final Clock clock;

    public CashEntryController(CashEntryService service, CashEntryCsvService csv, Clock clock) {
        this.service = service;
        this.csv = csv;
        this.clock = clock;
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

    /** Entries matching the list filters as CSV (UTF-8 with BOM, {@code ;}, ISO dates). */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @AuthenticationPrincipal AppPrincipal me,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) EntryKind kind,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String q) {
        byte[] body = csv.export(me.id(), new CashEntryService.Filter(from, to, kind, categoryId, q));
        return ResponseEntity.ok()
                .contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(csv.exportFileName(me.id(), LocalDate.now(clock))).build().toString())
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    /** First import step: parses the file and reports every row; stores nothing. */
    @PostMapping(value = "/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CashEntryCsvService.Preview preview(@AuthenticationPrincipal AppPrincipal me,
                                               @RequestParam("file") MultipartFile file) throws IOException {
        if (file.getSize() > CashEntryCsvService.MAX_BYTES) {
            // Also enforced by the multipart limit; checked before reading the bytes
            throw CashEntryCsvService.tooLarge();
        }
        return csv.preview(me.id(), file.getBytes());
    }

    /** Second import step: creates the confirmed entries, all or none. */
    @PostMapping("/import")
    @ResponseStatus(HttpStatus.CREATED)
    public ImportResponse importEntries(@AuthenticationPrincipal AppPrincipal me,
                                        @Valid @RequestBody ImportRequest body) {
        return new ImportResponse(service.importEntries(me.id(),
                body.entries().stream().map(EntryRequest::toData).toList()));
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

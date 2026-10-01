package me.luucka.finance.rule;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import me.luucka.finance.auth.AppPrincipal;
import me.luucka.finance.core.EntryKind;
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

/** The user's rules to categorize entries by their description, and suggestions from them and from the past. */
@RestController
@RequestMapping("/api/category-rules")
public class CategoryRuleController {

    public record RuleRequest(@NotNull @Size(max = 100) String pattern, @NotNull Long categoryId) {
    }

    private final CategoryRuleService service;

    public CategoryRuleController(CategoryRuleService service) {
        this.service = service;
    }

    @GetMapping
    public List<CategoryRuleService.RuleResponse> list(@AuthenticationPrincipal AppPrincipal me) {
        return service.list(me.id());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryRuleService.RuleResponse create(@AuthenticationPrincipal AppPrincipal me,
                                                   @Valid @RequestBody RuleRequest body) {
        return service.create(me.id(), body.pattern(), body.categoryId());
    }

    @PutMapping("/{id}")
    public CategoryRuleService.RuleResponse update(@AuthenticationPrincipal AppPrincipal me, @PathVariable long id,
                                                   @Valid @RequestBody RuleRequest body) {
        return service.update(me.id(), id, body.pattern(), body.categoryId());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AppPrincipal me, @PathVariable long id) {
        service.delete(me.id(), id);
        return ResponseEntity.noContent().build();
    }

    /** 204 when neither a rule nor the past entries suggest a category. */
    @GetMapping("/suggest")
    public ResponseEntity<CategoryRuleService.SuggestionResponse> suggest(@AuthenticationPrincipal AppPrincipal me,
                                                                          @RequestParam @Size(max = 500) String description,
                                                                          @RequestParam(required = false) EntryKind kind) {
        CategoryRuleService.SuggestionResponse suggestion = service.suggest(me.id(), description, kind);
        return suggestion == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(suggestion);
    }
}

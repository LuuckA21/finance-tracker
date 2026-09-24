package me.luucka.finance.category;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    static final String COLOR_PATTERN = "^#[0-9a-fA-F]{6}$";

    public record CreateCategoryRequest(
            @NotBlank @Size(max = 64) String name,
            @NotNull EntryKind kind,
            @NotNull @Pattern(regexp = COLOR_PATTERN) String color) {
    }

    public record UpdateCategoryRequest(
            @NotBlank @Size(max = 64) String name,
            @NotNull @Pattern(regexp = COLOR_PATTERN) String color) {
    }

    private final CategoryService service;

    public CategoryController(CategoryService service) {
        this.service = service;
    }

    @GetMapping
    public List<CategoryService.CategoryResponse> list(@AuthenticationPrincipal AppPrincipal me) {
        return service.list(me.id());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryService.CategoryResponse create(@AuthenticationPrincipal AppPrincipal me,
                                                   @Valid @RequestBody CreateCategoryRequest body) {
        return service.create(me.id(), body.name(), body.kind(), body.color());
    }

    @PutMapping("/{id}")
    public CategoryService.CategoryResponse update(@AuthenticationPrincipal AppPrincipal me, @PathVariable long id,
                                                   @Valid @RequestBody UpdateCategoryRequest body) {
        return service.update(me.id(), id, body.name(), body.color());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AppPrincipal me, @PathVariable long id) {
        service.delete(me.id(), id);
        return ResponseEntity.noContent().build();
    }
}

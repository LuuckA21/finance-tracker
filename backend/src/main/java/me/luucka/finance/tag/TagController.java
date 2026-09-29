package me.luucka.finance.tag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import me.luucka.finance.auth.AppPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The user's tags with their totals; tags are created by writing them on entries. */
@RestController
@RequestMapping("/api/tags")
public class TagController {

    public record RenameRequest(@NotNull @Size(max = 100) String name) {
    }

    private final TagService service;

    public TagController(TagService service) {
        this.service = service;
    }

    @GetMapping
    public TagService.TagsResponse list(@AuthenticationPrincipal AppPrincipal me) {
        return service.list(me.id());
    }

    @PutMapping("/{id}")
    public TagService.TagSummary rename(@AuthenticationPrincipal AppPrincipal me, @PathVariable long id,
                                        @Valid @RequestBody RenameRequest body) {
        return service.rename(me.id(), id, body.name());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AppPrincipal me, @PathVariable long id) {
        service.delete(me.id(), id);
        return ResponseEntity.noContent().build();
    }
}

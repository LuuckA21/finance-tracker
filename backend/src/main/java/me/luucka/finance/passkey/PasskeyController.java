package me.luucka.finance.passkey;

import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import me.luucka.finance.auth.AppPrincipal;
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
import tools.jackson.databind.JsonNode;

/** The logged-in user's passkeys: list, add (after the password and 2FA code), rename, remove. */
@RestController
@RequestMapping("/api/account/passkeys")
public class PasskeyController {

    /** {@code code}: the 2FA code (or a recovery code), needed only when 2FA is on. */
    public record OptionsRequest(@NotBlank @Size(max = 128) String password, @Size(max = 32) String code) {
    }

    /** {@code credential}: the browser's answer, {@code PublicKeyCredential.toJSON()}. */
    public record RegisterRequest(@NotBlank @Size(max = 64) String name, @NotNull JsonNode credential) {
    }

    public record RenameRequest(@NotBlank @Size(max = 64) String name) {
    }

    private final PasskeyService service;

    public PasskeyController(PasskeyService service) {
        this.service = service;
    }

    @GetMapping
    public List<PasskeyService.PasskeyResponse> list(@AuthenticationPrincipal AppPrincipal me) {
        return service.list(me.id());
    }

    @PostMapping("/options")
    public Map<String, Object> options(@AuthenticationPrincipal AppPrincipal me, @Valid @RequestBody OptionsRequest body,
                                       HttpServletRequest request) {
        return service.registrationOptions(me.id(), body.password(), body.code(), request.getSession());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PasskeyService.PasskeyResponse register(@AuthenticationPrincipal AppPrincipal me,
                                                   @Valid @RequestBody RegisterRequest body,
                                                   HttpServletRequest request) {
        return service.register(me.id(), body.name(), body.credential().toString(), request.getSession());
    }

    @PutMapping("/{id}")
    public PasskeyService.PasskeyResponse rename(@AuthenticationPrincipal AppPrincipal me, @PathVariable long id,
                                                 @Valid @RequestBody RenameRequest body) {
        return service.rename(me.id(), id, body.name());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AppPrincipal me, @PathVariable long id) {
        service.delete(me.id(), id);
        return ResponseEntity.noContent().build();
    }
}

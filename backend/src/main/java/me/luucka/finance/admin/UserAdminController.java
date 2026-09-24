package me.luucka.finance.admin;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import me.luucka.finance.auth.AppPrincipal;
import me.luucka.finance.user.Role;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Account administration. Access restricted to ROLE_ADMIN in {@code SecurityConfig}.
 */
@RestController
@RequestMapping("/api/admin/users")
public class UserAdminController {

    public record CreateUserRequest(
            @NotBlank @Size(max = 64) String username,
            @NotNull Role role,
            @Size(max = 128) String password) {
    }

    public record UpdateUserRequest(Role role, Boolean enabled) {
    }

    private final UserAdminService service;

    public UserAdminController(UserAdminService service) {
        this.service = service;
    }

    @GetMapping
    public List<UserAdminService.UserResponse> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserAdminService.UserWithPassword create(@Valid @RequestBody CreateUserRequest body) {
        String password = body.password() == null || body.password().isBlank() ? null : body.password();
        return service.create(body.username(), body.role(), password);
    }

    @PatchMapping("/{id}")
    public UserAdminService.UserResponse update(@AuthenticationPrincipal AppPrincipal me, @PathVariable long id,
                                                @Valid @RequestBody UpdateUserRequest body) {
        return service.update(me.id(), id, body.role(), body.enabled());
    }

    @PostMapping("/{id}/reset-password")
    public UserAdminService.UserWithPassword resetPassword(@PathVariable long id) {
        return service.resetPassword(id);
    }

    @PostMapping("/{id}/unlock")
    public UserAdminService.UserResponse unlock(@PathVariable long id) {
        return service.unlock(id);
    }

    @PostMapping("/{id}/reset-mfa")
    public UserAdminService.UserResponse resetMfa(@PathVariable long id) {
        return service.resetMfa(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AppPrincipal me, @PathVariable long id) {
        service.delete(me.id(), id);
        return ResponseEntity.noContent().build();
    }
}

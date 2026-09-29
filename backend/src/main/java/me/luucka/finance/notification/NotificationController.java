package me.luucka.finance.notification;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import me.luucka.finance.auth.AppPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The logged-in user's email notifications. */
@RestController
@RequestMapping("/api/account/notifications")
public class NotificationController {

    /** Partial update: omitted (null) fields keep their value. */
    public record UpdateRequest(Boolean budgetAlerts, Boolean goalAlerts, Boolean monthlySummary) {
    }

    public record EmailRequest(@NotBlank @Size(max = 254) String email) {
    }

    public record CodeRequest(@NotBlank @Size(max = 16) String code) {
    }

    private final NotificationSettingsService service;

    public NotificationController(NotificationSettingsService service) {
        this.service = service;
    }

    @GetMapping
    public NotificationSettingsService.SettingsResponse get(@AuthenticationPrincipal AppPrincipal me) {
        return service.get(me.id());
    }

    @PutMapping
    public NotificationSettingsService.SettingsResponse update(@AuthenticationPrincipal AppPrincipal me,
                                                               @Valid @RequestBody UpdateRequest body) {
        return service.update(me.id(), body.budgetAlerts(), body.goalAlerts(), body.monthlySummary());
    }

    /** Sends a confirmation code to the address. */
    @PostMapping("/email")
    public NotificationSettingsService.SettingsResponse requestCode(@AuthenticationPrincipal AppPrincipal me,
                                                                    @Valid @RequestBody EmailRequest body) {
        return service.requestCode(me.id(), body.email());
    }

    @PostMapping("/email/confirm")
    public NotificationSettingsService.SettingsResponse confirm(@AuthenticationPrincipal AppPrincipal me,
                                                                @Valid @RequestBody CodeRequest body) {
        return service.confirm(me.id(), body.code());
    }

    @DeleteMapping("/email")
    public NotificationSettingsService.SettingsResponse removeEmail(@AuthenticationPrincipal AppPrincipal me) {
        return service.removeEmail(me.id());
    }

    @PostMapping("/test")
    public ResponseEntity<Void> test(@AuthenticationPrincipal AppPrincipal me) {
        service.sendTest(me.id());
        return ResponseEntity.noContent().build();
    }
}

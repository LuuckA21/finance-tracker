package me.luucka.finance.account;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import me.luucka.finance.auth.AppPrincipal;
import me.luucka.finance.auth.MfaService;
import me.luucka.finance.common.CurrencyCode;
import me.luucka.finance.user.Language;
import me.luucka.finance.user.Theme;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-service endpoints of the logged-in user: password, preferences, 2FA, login history.
 */
@RestController
@RequestMapping("/api/account")
public class AccountController {

    public record ChangePasswordRequest(
            @NotBlank @Size(max = 128) String currentPassword,
            @NotBlank @Size(max = 128) String newPassword) {
    }

    /** Partial update: omitted (null) fields keep their current value. */
    public record SettingsRequest(@CurrencyCode String baseCurrency, Language language, Theme theme) {
    }

    public record MfaCodeRequest(@NotBlank @Size(max = 32) String code) {
    }

    public record DisableMfaRequest(
            @NotBlank @Size(max = 128) String password,
            @NotBlank @Size(max = 32) String code) {
    }

    public record RecoveryCodesResponse(List<String> recoveryCodes) {
    }

    private final AccountService accountService;
    private final MfaService mfaService;

    public AccountController(AccountService accountService, MfaService mfaService) {
        this.accountService = accountService;
        this.mfaService = mfaService;
    }

    @PutMapping("/password")
    public MeResponse changePassword(@AuthenticationPrincipal AppPrincipal me,
                                     @Valid @RequestBody ChangePasswordRequest body,
                                     HttpServletRequest request, HttpServletResponse response) {
        return accountService.changePassword(me.id(), body.currentPassword(), body.newPassword(), request, response);
    }

    @PutMapping("/settings")
    public MeResponse updateSettings(@AuthenticationPrincipal AppPrincipal me, @Valid @RequestBody SettingsRequest body) {
        return accountService.updateSettings(me.id(), body.baseCurrency(), body.language(), body.theme());
    }

    @GetMapping("/logins")
    public List<AccountService.LoginEventResponse> logins(@AuthenticationPrincipal AppPrincipal me) {
        return accountService.loginHistory(me.id());
    }

    @PostMapping("/mfa/setup")
    public MfaService.SetupResponse beginMfaSetup(@AuthenticationPrincipal AppPrincipal me) {
        return mfaService.beginSetup(me.id());
    }

    @PostMapping("/mfa/enable")
    public RecoveryCodesResponse enableMfa(@AuthenticationPrincipal AppPrincipal me,
                                           @Valid @RequestBody MfaCodeRequest body) {
        return new RecoveryCodesResponse(mfaService.confirmSetup(me.id(), body.code()));
    }

    @PostMapping("/mfa/recovery-codes")
    public RecoveryCodesResponse regenerateRecoveryCodes(@AuthenticationPrincipal AppPrincipal me,
                                                         @Valid @RequestBody MfaCodeRequest body) {
        return new RecoveryCodesResponse(mfaService.newRecoveryCodes(me.id(), body.code()));
    }

    @PostMapping("/mfa/disable")
    public ResponseEntity<Void> disableMfa(@AuthenticationPrincipal AppPrincipal me,
                                           @Valid @RequestBody DisableMfaRequest body) {
        accountService.disableMfa(me.id(), body.password(), body.code());
        return ResponseEntity.noContent().build();
    }
}

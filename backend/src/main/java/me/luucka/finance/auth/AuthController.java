package me.luucka.finance.auth;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import me.luucka.finance.account.AccountService;
import me.luucka.finance.account.MeResponse;
import me.luucka.finance.passkey.PasskeyService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record LoginRequest(
            @NotBlank @Size(max = 64) String username,
            @NotBlank @Size(max = 128) String password) {
    }

    public record MfaRequest(@NotBlank @Size(max = 32) String code) {
    }

    public record LoginResponse(boolean mfaRequired) {
    }

    /** {@code credential}: the browser's answer, {@code PublicKeyCredential.toJSON()}. */
    public record PasskeyLoginRequest(@NotNull JsonNode credential) {
    }

    /** What the login page can offer: passkeys need a configured public address. */
    public record ConfigResponse(boolean passkeys) {
    }

    private final AuthService authService;
    private final AccountService accountService;
    private final PasskeyService passkeyService;

    public AuthController(AuthService authService, AccountService accountService, PasskeyService passkeyService) {
        this.authService = authService;
        this.accountService = accountService;
        this.passkeyService = passkeyService;
    }

    @GetMapping("/config")
    public ConfigResponse config() {
        return new ConfigResponse(passkeyService.enabled());
    }

    /** Starts a passkey sign-in: options for {@code navigator.credentials.get()}. */
    @PostMapping("/passkey/options")
    public Map<String, Object> passkeyOptions(HttpServletRequest request) {
        return passkeyService.signInOptions(request.getSession());
    }

    @PostMapping("/passkey")
    public ResponseEntity<Void> loginWithPasskey(@Valid @RequestBody PasskeyLoginRequest body,
                                                 HttpServletRequest request, HttpServletResponse response) {
        authService.loginWithPasskey(body.credential().toString(), request, response);
        return ResponseEntity.noContent().build();
    }

    /**
     * Returns the CSRF token of the current session (creating the session if needed).
     * The SPA sends it back in the {@code X-CSRF-TOKEN} header on every mutating request.
     */
    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
                               HttpServletResponse response) {
        AuthService.Outcome outcome = authService.login(body.username(), body.password(), request, response);
        return new LoginResponse(outcome == AuthService.Outcome.MFA_REQUIRED);
    }

    @PostMapping("/login/mfa")
    public ResponseEntity<Void> loginMfa(@Valid @RequestBody MfaRequest body, HttpServletRequest request,
                                         HttpServletResponse response) {
        authService.verifyMfa(body.code(), request, response);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        authService.logout(request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal AppPrincipal principal) {
        return accountService.me(principal.id());
    }
}

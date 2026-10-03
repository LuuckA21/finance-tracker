package me.luucka.finance.passkey;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.AttestedCredentialDataConverter;
import com.webauthn4j.converter.exception.DataConversionException;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.credential.CredentialRecordImpl;
import com.webauthn4j.data.AuthenticationData;
import com.webauthn4j.data.AuthenticationParameters;
import com.webauthn4j.data.AuthenticatorTransport;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.RegistrationData;
import com.webauthn4j.data.RegistrationParameters;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.authenticator.AuthenticatorData;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.server.ServerProperty;
import com.webauthn4j.verifier.exception.VerificationException;
import jakarta.servlet.http.HttpSession;
import me.luucka.finance.auth.MfaService;
import me.luucka.finance.auth.ReauthGuard;
import me.luucka.finance.common.ApiException;
import me.luucka.finance.user.AppUser;
import me.luucka.finance.user.AppUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Passkeys (WebAuthn): adding them to an account and signing in with them.
 * <p>
 * Every ceremony has a random challenge kept in the session, used once and valid a few minutes.
 * The device must verify its user (face, fingerprint or PIN), so a passkey stands for both factors
 * and a sign-in with it needs no 2FA code. Passkeys are discoverable: signing in needs no username.
 * Adding one needs the password, and the 2FA code when 2FA is on, since it gives access without them.
 */
@Service
public class PasskeyService {

    /** Passkeys an account can have. */
    static final int MAX_PASSKEYS = 10;
    /** How long the browser (and the server) wait for the device. */
    static final Duration TIMEOUT = Duration.ofMinutes(5);

    private static final List<PublicKeyCredentialParameters> ALGORITHMS = List.of(
            new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256),
            new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.EdDSA),
            new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.RS256));
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    public record PasskeyResponse(long id, String name, Instant createdAt, Instant lastUsedAt, boolean synced) {
        static PasskeyResponse of(Passkey p) {
            return new PasskeyResponse(p.getId(), p.getName(), p.getCreatedAt(), p.getLastUsedAt(), p.isBackedUp());
        }
    }

    /** A sign-in that failed; {@code userId} when the passkey was known (for the login history). */
    public static final class Rejected extends RuntimeException {
        private final Long userId;

        Rejected(Long userId, String message) {
            super(message, null, false, false);
            this.userId = userId;
        }

        public Long userId() {
            return userId;
        }
    }

    private final PasskeyRepository passkeys;
    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final MfaService mfaService;
    private final ReauthGuard reauthGuard;
    private final RelyingParty relyingParty;
    private final SecureRandom random;
    private final Clock clock;
    private final ObjectConverter converter = new ObjectConverter();
    private final WebAuthnManager webAuthn = WebAuthnManager.createNonStrictWebAuthnManager(converter);
    private final AttestedCredentialDataConverter credentialDataConverter = new AttestedCredentialDataConverter(converter);

    public PasskeyService(PasskeyRepository passkeys, AppUserRepository users, PasswordEncoder passwordEncoder,
                          MfaService mfaService, ReauthGuard reauthGuard, RelyingParty relyingParty,
                          SecureRandom random, Clock clock) {
        this.passkeys = passkeys;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.mfaService = mfaService;
        this.reauthGuard = reauthGuard;
        this.relyingParty = relyingParty;
        this.random = random;
        this.clock = clock;
    }

    public boolean enabled() {
        return relyingParty.enabled();
    }

    @Transactional(readOnly = true)
    public List<PasskeyResponse> list(long userId) {
        return passkeys.findByUserIdOrderByCreatedAtAsc(userId).stream().map(PasskeyResponse::of).toList();
    }

    /**
     * Starts adding a passkey: checks the password (and the 2FA code when 2FA is on) and returns
     * the options for {@code navigator.credentials.create()}.
     */
    @Transactional
    public Map<String, Object> registrationOptions(long userId, String password, String code, HttpSession session) {
        requireEnabled();
        AppUser user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        if (passkeys.countByUserId(userId) >= MAX_PASSKEYS) {
            throw ApiException.badRequest("too_many_passkeys", "At most " + MAX_PASSKEYS + " passkeys");
        }
        reauthGuard.ensureNotLocked(user);
        if (password == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw reauthGuard.failure(userId,
                    ApiException.badRequest("invalid_current_password", "Current password is wrong"));
        }
        if (user.isTotpEnabled() && (code == null
                || mfaService.verifySecondFactor(userId, code) == MfaService.Verification.INVALID)) {
            throw reauthGuard.failure(userId, ApiException.badRequest("invalid_mfa_code", "Invalid code"));
        }
        user.resetFailedLogins();
        if (user.getWebauthnUserHandle() == null) {
            user.setWebauthnUserHandle(randomBytes(32));
        }
        byte[] challenge = randomBytes(32);
        session.setAttribute(PasskeyChallenge.REGISTRATION,
                new PasskeyChallenge(challenge, userId, clock.instant().plus(TIMEOUT)));

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("rp", Map.of("id", relyingParty.id(), "name", RelyingParty.NAME));
        options.put("user", Map.of("id", B64.encodeToString(user.getWebauthnUserHandle()),
                "name", user.getUsername(), "displayName", user.getUsername()));
        options.put("challenge", B64.encodeToString(challenge));
        options.put("pubKeyCredParams", ALGORITHMS.stream()
                .map(a -> Map.of("type", "public-key", "alg", a.getAlg().getValue())).toList());
        options.put("timeout", TIMEOUT.toMillis());
        // The same device twice would only add a duplicate
        options.put("excludeCredentials", passkeys.findByUserIdOrderByCreatedAtAsc(userId).stream()
                .map(p -> descriptor(p)).toList());
        options.put("authenticatorSelection", Map.of("residentKey", "required", "requireResidentKey", true,
                "userVerification", "required"));
        options.put("attestation", "none");
        return options;
    }

    /** Finishes adding a passkey with the browser's answer ({@code PublicKeyCredential.toJSON()}). */
    @Transactional
    public PasskeyResponse register(long userId, String name, String credentialJson, HttpSession session) {
        requireEnabled();
        PasskeyChallenge challenge = take(session, PasskeyChallenge.REGISTRATION);
        if (challenge == null || !Long.valueOf(userId).equals(challenge.userId())) {
            throw ApiException.badRequest("passkey_expired", "Start adding the passkey again");
        }
        RegistrationData data;
        try {
            data = webAuthn.verifyRegistrationResponseJSON(credentialJson,
                    new RegistrationParameters(serverProperty(challenge), ALGORITHMS, true, true));
        } catch (DataConversionException | VerificationException e) {
            throw ApiException.badRequest("passkey_invalid", "The device's answer could not be verified");
        }
        AuthenticatorData<?> authData = data.getAttestationObject().getAuthenticatorData();
        AttestedCredentialData credential = authData.getAttestedCredentialData();
        if (passkeys.existsByCredentialId(credential.getCredentialId())) {
            throw ApiException.conflict("passkey_exists", "This passkey is already registered");
        }
        String transports = data.getTransports() == null ? null : data.getTransports().stream()
                .map(AuthenticatorTransport::getValue).sorted().collect(Collectors.joining(","));
        Passkey passkey = new Passkey(userId, credential.getCredentialId(), credentialDataConverter.convert(credential),
                authData.getSignCount(), authData.isFlagUV(), authData.isFlagBE(), authData.isFlagBS(),
                transports == null || transports.isEmpty() ? null : transports, name.strip());
        return PasskeyResponse.of(passkeys.save(passkey));
    }

    @Transactional
    public PasskeyResponse rename(long userId, long id, String name) {
        Passkey passkey = own(userId, id);
        passkey.setName(name.strip());
        return PasskeyResponse.of(passkey);
    }

    @Transactional
    public void delete(long userId, long id) {
        passkeys.delete(own(userId, id));
    }

    /** Removes every passkey of the user (an administrator reset their password or 2FA). */
    @Transactional
    public void deleteAll(long userId) {
        passkeys.deleteByUser(userId);
    }

    /** Starts a sign-in: options for {@code navigator.credentials.get()}, for any passkey of this site. */
    public Map<String, Object> signInOptions(HttpSession session) {
        requireEnabled();
        byte[] challenge = randomBytes(32);
        session.setAttribute(PasskeyChallenge.SIGN_IN, new PasskeyChallenge(challenge, null,
                clock.instant().plus(TIMEOUT)));
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("challenge", B64.encodeToString(challenge));
        options.put("rpId", relyingParty.id());
        options.put("timeout", TIMEOUT.toMillis());
        options.put("userVerification", "required");
        options.put("allowCredentials", List.of());
        return options;
    }

    /**
     * Checks the browser's answer to a sign-in and returns the user it belongs to.
     *
     * @throws Rejected when the answer is not a valid one for a passkey of this site
     */
    @Transactional(noRollbackFor = Rejected.class)
    public long verifySignIn(String credentialJson, HttpSession session) {
        requireEnabled();
        PasskeyChallenge challenge = session == null ? null : take(session, PasskeyChallenge.SIGN_IN);
        if (challenge == null) {
            throw new Rejected(null, "No sign-in in progress");
        }
        AuthenticationData data;
        try {
            data = webAuthn.parseAuthenticationResponseJSON(credentialJson);
        } catch (DataConversionException e) {
            throw new Rejected(null, "Unreadable answer");
        }
        Passkey passkey = data.getCredentialId() == null ? null
                : passkeys.findByCredentialId(data.getCredentialId()).orElse(null);
        if (passkey == null) {
            throw new Rejected(null, "Unknown passkey");
        }
        long userId = passkey.getUserId();
        byte[] handle = users.findById(userId).map(AppUser::getWebauthnUserHandle).orElse(null);
        if (data.getUserHandle() != null && !Arrays.equals(data.getUserHandle(), handle)) {
            throw new Rejected(userId, "User handle does not match");
        }
        CredentialRecordImpl record = new CredentialRecordImpl(new NoneAttestationStatement(),
                passkey.isUvInitialized(), passkey.isBackupEligible(), passkey.isBackedUp(), passkey.getSignCount(),
                credentialDataConverter.convert(passkey.getCredentialData()), null, null, null, transports(passkey));
        try {
            webAuthn.verify(data, new AuthenticationParameters(serverProperty(challenge), record, null, true, true));
        } catch (VerificationException e) {
            throw new Rejected(userId, "Verification failed: " + e.getClass().getSimpleName());
        }
        AuthenticatorData<?> authData = data.getAuthenticatorData();
        passkey.used(authData.getSignCount(), authData.isFlagBS(), clock.instant());
        return userId;
    }

    private Passkey own(long userId, long id) {
        return passkeys.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Passkey"));
    }

    private void requireEnabled() {
        if (!relyingParty.enabled()) {
            throw ApiException.conflict("passkeys_disabled", "Passkeys are not configured on this server");
        }
    }

    /** The challenge of the ceremony, removed from the session: each is used at most once. */
    private PasskeyChallenge take(HttpSession session, String attribute) {
        Object value = session.getAttribute(attribute);
        session.removeAttribute(attribute);
        return value instanceof PasskeyChallenge c && !c.isExpired(clock.instant()) ? c : null;
    }

    private ServerProperty serverProperty(PasskeyChallenge challenge) {
        return new ServerProperty(relyingParty.origin(), relyingParty.id(), new DefaultChallenge(challenge.value()));
    }

    private static Map<String, Object> descriptor(Passkey p) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("type", "public-key");
        d.put("id", B64.encodeToString(p.getCredentialId()));
        if (p.getTransports() != null) {
            d.put("transports", List.of(p.getTransports().split(",")));
        }
        return d;
    }

    private static Set<AuthenticatorTransport> transports(Passkey p) {
        return p.getTransports() == null ? null : Arrays.stream(p.getTransports().split(","))
                .map(AuthenticatorTransport::create).collect(Collectors.toSet());
    }

    private byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }
}

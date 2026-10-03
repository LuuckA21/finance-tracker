package me.luucka.finance.support;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.webauthn4j.converter.AttestationObjectConverter;
import com.webauthn4j.converter.AuthenticatorDataConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.attestation.AttestationObject;
import com.webauthn4j.data.attestation.authenticator.AAGUID;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.authenticator.AuthenticatorData;
import com.webauthn4j.data.attestation.authenticator.EC2COSEKey;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement;
import com.webauthn4j.data.extension.authenticator.AuthenticationExtensionAuthenticatorOutput;
import com.webauthn4j.data.extension.authenticator.RegistrationExtensionAuthenticatorOutput;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A passkey device for tests: answers the server's options as a browser with a platform
 * authenticator would ({@code PublicKeyCredential.toJSON()}), with a P-256 key, "none" attestation,
 * user verified, and a signature counter that grows with each sign-in.
 */
public final class TestAuthenticator {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ObjectConverter CONVERTER = new ObjectConverter();
    private static final byte FLAGS = (byte) (AuthenticatorData.BIT_UP | AuthenticatorData.BIT_UV);

    private final String origin;
    private final KeyPair keys;
    private final byte[] credentialId = new byte[16];
    private byte[] userHandle;
    private long counter;

    public TestAuthenticator(String origin) {
        this.origin = origin;
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            this.keys = generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        new SecureRandom().nextBytes(credentialId);
    }

    /** The answer to {@code navigator.credentials.create(options)}. */
    public String register(String optionsJson) {
        JsonNode options = JSON.readTree(optionsJson);
        userHandle = B64D.decode(options.path("user").path("id").asString());
        AttestedCredentialData credential = new AttestedCredentialData(AAGUID.ZERO, credentialId,
                EC2COSEKey.create((ECPublicKey) keys.getPublic(), COSEAlgorithmIdentifier.ES256));
        AuthenticatorData<RegistrationExtensionAuthenticatorOutput> authData = new AuthenticatorData<>(
                sha256(options.path("rp").path("id").asString()), (byte) (FLAGS | AuthenticatorData.BIT_AT),
                counter, credential);
        byte[] attestation = new AttestationObjectConverter(CONVERTER)
                .convertToBytes(new AttestationObject(authData, new NoneAttestationStatement()));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("clientDataJSON", B64.encodeToString(clientData("webauthn.create", options)));
        response.put("attestationObject", B64.encodeToString(attestation));
        response.put("transports", List.of("internal"));
        return credential(response);
    }

    /** The answer to {@code navigator.credentials.get(options)}. */
    public String signIn(String optionsJson) {
        JsonNode options = JSON.readTree(optionsJson);
        counter++;
        byte[] authData = new AuthenticatorDataConverter(CONVERTER).convert(
                new AuthenticatorData<AuthenticationExtensionAuthenticatorOutput>(
                        sha256(options.path("rpId").asString()), FLAGS, counter));
        byte[] clientData = clientData("webauthn.get", options);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("clientDataJSON", B64.encodeToString(clientData));
        response.put("authenticatorData", B64.encodeToString(authData));
        response.put("signature", B64.encodeToString(sign(ByteBuffer.allocate(authData.length + 32)
                .put(authData).put(sha256(clientData)).array())));
        // A device never registered still answers, with a user id of its own
        response.put("userHandle", B64.encodeToString(userHandle == null ? credentialId : userHandle));
        return credential(response);
    }

    private String credential(Map<String, Object> response) {
        Map<String, Object> credential = new LinkedHashMap<>();
        credential.put("id", B64.encodeToString(credentialId));
        credential.put("rawId", B64.encodeToString(credentialId));
        credential.put("type", "public-key");
        credential.put("response", response);
        credential.put("clientExtensionResults", Map.of());
        credential.put("authenticatorAttachment", "platform");
        return JSON.writeValueAsString(credential);
    }

    private byte[] clientData(String type, JsonNode options) {
        return JSON.writeValueAsString(Map.of("type", type, "challenge", options.path("challenge").asString(),
                "origin", origin, "crossOrigin", false)).getBytes(StandardCharsets.UTF_8);
    }

    private byte[] sign(byte[] data) {
        try {
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initSign(keys.getPrivate());
            signature.update(data);
            return signature.sign();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

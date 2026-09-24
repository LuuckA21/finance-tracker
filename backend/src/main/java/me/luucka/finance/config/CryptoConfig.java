package me.luucka.finance.config;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Map;

import me.luucka.finance.core.security.AesGcmCipher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
public class CryptoConfig {

    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    SecureRandom secureRandom() {
        return new SecureRandom();
    }

    /**
     * Argon2id with OWASP recommended minimum parameters (19 MiB memory, 2 iterations).
     * Hashes are prefixed with {@code {argon2}} so the algorithm can be upgraded later.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        Argon2PasswordEncoder argon2 = new Argon2PasswordEncoder(16, 32, 1, 19_456, 2);
        return new DelegatingPasswordEncoder("argon2", Map.of(
                "argon2", argon2,
                "bcrypt", new BCryptPasswordEncoder()));
    }

    @Bean
    AesGcmCipher secretCipher(AppProperties properties, SecureRandom random) {
        String key = properties.encryptionKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "app.encryption-key (env APP_ENCRYPTION_KEY) is not set. Generate one with: openssl rand -base64 32");
        }
        return AesGcmCipher.fromBase64Key(key, random);
    }
}

package me.luucka.finance.passkey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashSet;
import java.util.Set;

import com.webauthn4j.data.AuthenticatorTransport;
import org.junit.jupiter.api.Test;

class PasskeyServiceTest {

    @Test
    void onlyKnownTransportsAreKept() {
        Set<AuthenticatorTransport> reported = new HashSet<>(Set.of(AuthenticatorTransport.INTERNAL,
                AuthenticatorTransport.HYBRID, AuthenticatorTransport.create("x".repeat(500)),
                AuthenticatorTransport.create("cable")));
        assertEquals("hybrid,internal", PasskeyService.transportsOf(reported));
        assertEquals("usb", PasskeyService.transportsOf(Set.of(AuthenticatorTransport.USB)));
        assertNull(PasskeyService.transportsOf(Set.of(AuthenticatorTransport.create("cable"))));
        assertNull(PasskeyService.transportsOf(Set.of()));
        assertNull(PasskeyService.transportsOf(null));
        // All six known ones fit the column (100 characters)
        String all = PasskeyService.transportsOf(Set.of(AuthenticatorTransport.USB, AuthenticatorTransport.NFC,
                AuthenticatorTransport.BLE, AuthenticatorTransport.SMART_CARD, AuthenticatorTransport.HYBRID,
                AuthenticatorTransport.INTERNAL));
        assertEquals("ble,hybrid,internal,nfc,smart-card,usb", all);
    }
}

package me.luucka.finance.passkey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RelyingPartyTest {

    @Test
    void theDomainAndOriginComeFromThePublicAddress() {
        RelyingParty rp = new RelyingParty("https://Finanze.Example.com/");
        assertTrue(rp.enabled());
        assertEquals("finanze.example.com", rp.id());
        assertEquals("https://finanze.example.com", rp.origin().toString());

        RelyingParty port = new RelyingParty("https://finanze.example.com:8443/app");
        assertEquals("finanze.example.com", port.id());
        assertEquals("https://finanze.example.com:8443", port.origin().toString());

        // Plain HTTP only for local development
        assertEquals("http://localhost:4173", new RelyingParty("http://localhost:4173").origin().toString());
    }

    @Test
    void passkeysAreOffWithoutAUsableAddress() {
        assertFalse(new RelyingParty((String) null).enabled());
        assertFalse(new RelyingParty(" ").enabled());
        assertFalse(new RelyingParty("http://finanze.example.com").enabled());
        assertFalse(new RelyingParty("https://192.168.1.10").enabled());
        assertFalse(new RelyingParty("finanze.example.com").enabled());
        assertFalse(new RelyingParty("ftp://finanze.example.com").enabled());
    }
}

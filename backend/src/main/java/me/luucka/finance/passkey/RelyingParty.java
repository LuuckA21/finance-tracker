package me.luucka.finance.passkey;

import java.net.URI;
import java.util.Locale;

import com.webauthn4j.data.client.Origin;
import me.luucka.finance.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Where passkeys belong (the WebAuthn relying party): the domain of {@code app.public-url} and its
 * origin. Browsers bind a passkey to that domain, so passkeys work only on that address, over
 * HTTPS (plain HTTP only on {@code localhost}, for development). Off while the address is unset.
 */
@Component
public class RelyingParty {

    private static final Logger log = LoggerFactory.getLogger(RelyingParty.class);

    /** Name the browser shows when creating a passkey. */
    static final String NAME = "Finanze";

    private final String id;
    private final Origin origin;

    @Autowired
    public RelyingParty(AppProperties properties) {
        this(properties.publicUrl());
    }

    RelyingParty(String publicUrl) {
        URI uri = parse(publicUrl);
        this.id = uri == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
        this.origin = uri == null ? null : new Origin(uri.getScheme().toLowerCase(Locale.ROOT) + "://" + id
                + (uri.getPort() == -1 ? "" : ":" + uri.getPort()));
        if (uri == null && publicUrl != null && !publicUrl.isBlank()) {
            log.warn("APP_PUBLIC_URL '{}' is not an https:// address (or http://localhost): passkeys are off",
                    publicUrl);
        }
    }

    public boolean enabled() {
        return id != null;
    }

    /** The domain passkeys are bound to (WebAuthn RP ID). */
    String id() {
        return id;
    }

    Origin origin() {
        return origin;
    }

    /** An https address with a host name, or http on localhost; null otherwise. */
    private static URI parse(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(url.strip());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            if (host == null || host.isEmpty() || host.matches("[0-9.]+") || host.contains(":")) {
                return null; // WebAuthn needs a domain name, not an IP address
            }
            boolean local = "localhost".equalsIgnoreCase(host);
            return "https".equals(scheme) || ("http".equals(scheme) && local) ? uri : null;
        } catch (java.net.URISyntaxException e) {
            return null;
        }
    }
}

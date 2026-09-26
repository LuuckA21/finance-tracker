package me.luucka.finance.fx;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import me.luucka.finance.config.AppProperties;
import me.luucka.finance.core.fx.EcbXmlParser;
import org.springframework.stereotype.Component;

/** Downloads the ECB reference rate feeds. */
@Component
public class EcbClient {

    public enum Feed {
        /** Latest working day (~2 KB). */
        DAILY("eurofxref-daily.xml"),
        /** Last 90 days, to fill short gaps (~150 KB). */
        LAST_90_DAYS("eurofxref-hist-90d.xml"),
        /** Everything since 1999, for the first import (a few MB). */
        HISTORY("eurofxref-hist.xml");

        private final String file;

        Feed(String file) {
            this.file = file;
        }
    }

    /** Guards against a misbehaving server; the full history is well below this. */
    private static final long MAX_BYTES = 64L * 1024 * 1024;

    private final HttpClient http;
    private final URI baseUrl;

    public EcbClient(AppProperties properties) {
        this.baseUrl = properties.fx().ecb().baseUrl();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .proxy(ProxySelector.getDefault())
                .build();
    }

    /**
     * @throws IOException              on network errors or a non-200 answer
     * @throws IllegalArgumentException when the answer is not a valid feed
     */
    public List<EcbXmlParser.Rate> fetch(Feed feed) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(baseUrl.resolve(feed.file))
                .timeout(Duration.ofMinutes(2))
                .header("Accept", "application/xml, text/xml")
                .header("User-Agent", "finance-tracker")
                .GET()
                .build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = new LimitedInputStream(response.body(), MAX_BYTES)) {
            if (response.statusCode() != 200) {
                throw new IOException("ECB answered HTTP " + response.statusCode() + " for " + feed.file);
            }
            return EcbXmlParser.parse(body);
        }
    }

    /** Fails once more than {@code limit} bytes have been read. */
    private static final class LimitedInputStream extends FilterInputStream {

        private long remaining;

        LimitedInputStream(InputStream in, long limit) {
            super(in);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                consume(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = super.read(buffer, offset, length);
            if (n > 0) {
                consume(n);
            }
            return n;
        }

        private void consume(int n) throws IOException {
            remaining -= n;
            if (remaining < 0) {
                throw new IOException("ECB feed larger than " + MAX_BYTES + " bytes");
            }
        }
    }
}

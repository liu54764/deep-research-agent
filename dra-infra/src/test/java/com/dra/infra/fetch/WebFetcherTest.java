package com.dra.infra.fetch;

import com.dra.infra.http.OutboundHttpClient;
import com.dra.infra.http.OutboundHttpProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebFetcherTest {

    private static final String HTML = """
            <html><head><title>Test Page</title></head>
            <body>
              <nav>menu</nav>
              <article>
                <h1>Durable execution</h1>
                <p>Durable execution recovers workflow state after a process crash.</p>
                <p>LangGraph checkpoints persist graph state for long-running agents.</p>
              </article>
              <footer>footer</footer>
            </body></html>
            """;

    private static HttpServer server;

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/page", exchange -> {
            byte[] body = HTML.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/denied", exchange -> {
            // LinkedIn's anti-bot status; HttpServer passes non-standard codes through.
            exchange.sendResponseHeaders(999, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @Test
    void fetchesAndExtractsReadableText() {
        WebFetcher fetcher = fetcher(true);
        FetchedPage page = fetcher.fetch("http://127.0.0.1:" + server.getAddress().getPort() + "/page");

        assertTrue(page.ok(), page.errorCode());
        assertEquals("Test Page", page.title());
        assertTrue(page.content().contains("Durable execution recovers workflow state"));
        assertTrue(page.content().contains("LangGraph checkpoints"));
        assertFalse(page.content().contains("menu"));
    }

    @Test
    void blocksPrivateAddressesByDefault() {
        WebFetcher fetcher = fetcher(false);
        FetchedPage page = fetcher.fetch("http://127.0.0.1:" + server.getAddress().getPort() + "/page");

        assertFalse(page.ok());
        assertEquals("FETCH_BLOCKED_URL", page.errorCode());
    }

    @Test
    void treatsStandardServerErrorsAsRetryable() {
        assertTrue(WebFetcher.isRetryableStatus(500));
        assertTrue(WebFetcher.isRetryableStatus(502));
        assertTrue(WebFetcher.isRetryableStatus(599));
    }

    @Test
    void treatsVendorAccessDenialsAsPermanent() {
        // LinkedIn answers non-browser requests with 999; retrying it never succeeds.
        assertFalse(WebFetcher.isRetryableStatus(999));
        assertFalse(WebFetcher.isRetryableStatus(1020)); // Cloudflare access denied
        assertFalse(WebFetcher.isRetryableStatus(403));
        assertFalse(WebFetcher.isRetryableStatus(429));
    }

    @Test
    void reportsBlockedStatusWithoutThrowingSoTemporalDoesNotRetry() {
        WebFetcher fetcher = fetcher(true);
        FetchedPage page = fetcher.fetch("http://127.0.0.1:" + server.getAddress().getPort() + "/denied");

        assertFalse(page.ok());
        assertEquals("FETCH_HTTP_999", page.errorCode());
        assertEquals(999, page.httpStatus());
    }

    private static WebFetcher fetcher(boolean allowPrivateForTests) {
        FetchProperties properties = new FetchProperties();
        properties.setAllowPrivateForTests(allowPrivateForTests);
        OutboundHttpProperties http = new OutboundHttpProperties();
        return new WebFetcher(new OutboundHttpClient(http), properties);
    }
}

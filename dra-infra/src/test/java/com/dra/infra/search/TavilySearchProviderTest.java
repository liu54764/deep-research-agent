package com.dra.infra.search;

import com.dra.contracts.dto.SearchResult;
import com.dra.infra.http.OutboundHttpClient;
import com.dra.infra.http.OutboundHttpProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TavilySearchProviderTest {

    private static HttpServer server;

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            String body = """
                    {"results":[
                      {"url":"https://example.com/a","title":"A","content":"snippet a","score":0.9},
                      {"url":"https://example.com/b","title":"B","content":"snippet b","score":0.8},
                      {"url":"https://www.linkedin.com/in/dan-kafka","title":"Dan Kafka","content":"profile","score":0.99},
                      {"url":"https://example.com/low","title":"Low","content":"weak","score":0.1}
                    ]}
                    """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @Test
    void parsesTavilyResults() {
        SearchResult result = provider(properties()).search("durable execution", 2);

        assertEquals("tavily", result.provider());
        assertEquals(3, result.hits().size());
        assertEquals("https://example.com/a", result.hits().get(0).url());
        assertTrue(result.hits().get(0).rank() < result.hits().get(1).rank());
    }

    @Test
    void dropsConfiguredNoiseDomainsByDefault() {
        SearchResult result = provider(properties()).search("kafka", 10);

        assertTrue(result.hits().stream()
                .noneMatch(hit -> hit.url().contains("linkedin.com")));
    }

    @Test
    void minScoreDropsWeakResults() {
        SearchProperties properties = properties();
        properties.setExcludedDomains(List.of());
        properties.setMinScore(0.5);

        SearchResult result = provider(properties).search("kafka", 10);

        assertEquals(3, result.hits().size());
        assertTrue(result.hits().stream().noneMatch(hit -> hit.url().endsWith("/low")));
    }

    private static SearchProperties properties() {
        SearchProperties properties = new SearchProperties();
        properties.setProvider("tavily");
        properties.setTavilyApiKey("test-key");
        properties.setTavilyBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        return properties;
    }

    private static TavilySearchProvider provider(SearchProperties properties) {
        return new TavilySearchProvider(
                new OutboundHttpClient(new OutboundHttpProperties()), properties,
                new ObjectMapper());
    }
}

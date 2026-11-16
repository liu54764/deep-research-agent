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
                      {"url":"https://example.com/b","title":"B","content":"snippet b","score":0.8}
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
        SearchProperties properties = new SearchProperties();
        properties.setProvider("tavily");
        properties.setTavilyApiKey("test-key");
        properties.setTavilyBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        TavilySearchProvider provider = new TavilySearchProvider(
                new OutboundHttpClient(new OutboundHttpProperties()), properties, new ObjectMapper());

        SearchResult result = provider.search("durable execution", 2);

        assertEquals("tavily", result.provider());
        assertEquals(2, result.hits().size());
        assertEquals("https://example.com/a", result.hits().get(0).url());
        assertTrue(result.hits().get(0).rank() < result.hits().get(1).rank());
    }
}

package com.dra.infra.search;

import com.dra.common.error.DraException;
import com.dra.common.error.ErrorCode;
import com.dra.contracts.dto.SearchHit;
import com.dra.contracts.dto.SearchResult;
import com.dra.contracts.dto.Usage;
import com.dra.domain.port.SearchPort;
import com.dra.infra.http.OutboundHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "dra.search", name = "provider", havingValue = "tavily")
public class TavilySearchProvider implements SearchPort {

    private final OutboundHttpClient http;
    private final SearchProperties properties;
    private final ObjectMapper objectMapper;

    public TavilySearchProvider(OutboundHttpClient http, SearchProperties properties,
                                ObjectMapper objectMapper) {
        if (properties.getTavilyApiKey() == null || properties.getTavilyApiKey().isBlank()) {
            throw new IllegalStateException(
                    "dra.search.provider=tavily requires TAVILY_API_KEY (or dra.search.tavily-api-key)");
        }
        this.http = http;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public SearchResult search(String query, int topK) {
        long startedAt = System.nanoTime();
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("query", query);
            body.put("max_results", Math.max(1, Math.min(topK, 20)));
            body.put("search_depth", properties.getTavilySearchDepth());
            body.put("include_answer", false);
            body.put("include_raw_content", false);

            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(properties.getTavilyBaseUrl() + "/search"))
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + properties.getTavilyApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = http.client().send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long latencyMs = (System.nanoTime() - startedAt) / 1_000_000L;
            if (response.statusCode() != 200) {
                boolean retryable = response.statusCode() >= 500 || response.statusCode() == 429;
                throw new DraException(ErrorCode.SEARCH_UNAVAILABLE,
                        "Tavily HTTP " + response.statusCode() + ": " + truncate(response.body(), 300),
                        retryable);
            }

            JsonNode root = objectMapper.readTree(response.body());
            List<SearchHit> hits = new ArrayList<>();
            JsonNode results = root.path("results");
            int rank = 1;
            for (JsonNode result : results) {
                String url = result.path("url").asText("");
                if (url.isBlank()) {
                    continue;
                }
                hits.add(new SearchHit(
                        url,
                        result.path("title").asText(""),
                        result.path("content").asText(""),
                        rank,
                        "tavily",
                        result.path("published_date").asText(null)));
                rank++;
            }
            return new SearchResult(hits, "tavily", Usage.ZERO, latencyMs);
        } catch (DraException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DraException(ErrorCode.SEARCH_UNAVAILABLE, "Tavily interrupted", true, e);
        } catch (Exception e) {
            throw new DraException(ErrorCode.SEARCH_UNAVAILABLE,
                    "Tavily request failed: " + e.getMessage(), true, e);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}

package com.dra.infra.http;

import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.time.Duration;
import java.net.http.HttpClient;

/**
 * One outbound HTTP client for search and fetch. Honors DRA_HTTP_PROXY so the
 * agent can fetch pages that need a local proxy (e.g. Clash on 127.0.0.1:7890).
 */
@Component
public class OutboundHttpClient {

    private final HttpClient client;

    public OutboundHttpClient(OutboundHttpProperties properties) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER);

        String proxy = properties.getProxy();
        if (proxy != null && !proxy.isBlank()) {
            URI uri = URI.create(proxy.contains("://") ? proxy : "http://" + proxy);
            if (uri.getHost() != null) {
                int port = uri.getPort() > 0 ? uri.getPort() : 7890;
                builder.proxy(ProxySelector.of(new InetSocketAddress(uri.getHost(), port)));
            }
        }
        this.client = builder.build();
    }

    public HttpClient client() {
        return client;
    }
}

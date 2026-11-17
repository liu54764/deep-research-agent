package com.dra.infra.search;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "dra.search")
public class SearchProperties {

    private String provider = "mock";
    private String tavilyApiKey = "";
    private String tavilyBaseUrl = "https://api.tavily.com";
    private String tavilySearchDepth = "basic";
    private int timeoutSeconds = 30;
    /**
     * Noise domains dropped before hits enter the candidate pool. LinkedIn profile pages match
     * person names ("Dan Kafka") on topic queries and otherwise consume fetch slots.
     */
    private List<String> excludedDomains = List.of("linkedin.com");
    /** Tavily relevance score floor; 0 disables the filter until it is validated on real data. */
    private double minScore;

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getTavilyApiKey() {
        return tavilyApiKey;
    }

    public void setTavilyApiKey(String tavilyApiKey) {
        this.tavilyApiKey = tavilyApiKey;
    }

    public String getTavilyBaseUrl() {
        return tavilyBaseUrl;
    }

    public void setTavilyBaseUrl(String tavilyBaseUrl) {
        this.tavilyBaseUrl = tavilyBaseUrl;
    }

    public String getTavilySearchDepth() {
        return tavilySearchDepth;
    }

    public void setTavilySearchDepth(String tavilySearchDepth) {
        this.tavilySearchDepth = tavilySearchDepth;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public List<String> getExcludedDomains() {
        return excludedDomains;
    }

    public void setExcludedDomains(List<String> excludedDomains) {
        this.excludedDomains = excludedDomains == null ? List.of() : List.copyOf(excludedDomains);
    }

    public double getMinScore() {
        return minScore;
    }

    public void setMinScore(double minScore) {
        this.minScore = minScore;
    }
}

package com.dra.infra.search;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dra.search")
public class SearchProperties {

    private String provider = "mock";
    private String tavilyApiKey = "";
    private String tavilyBaseUrl = "https://api.tavily.com";
    private String tavilySearchDepth = "basic";
    private int timeoutSeconds = 30;

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
}

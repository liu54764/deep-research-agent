package com.dra.infra.fetch;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dra.fetch")
public class FetchProperties {

    private String mode = "stub";
    private int timeoutSeconds = 20;
    private long maxBytes = 3L * 1024 * 1024;
    private int maxRedirects = 3;
    private String userAgent = "DeepResearchAgent/0.1 (research bot; contact: local-dev)";
    /** Test-only escape hatch; never enable in production. */
    private boolean allowPrivateForTests = false;

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public long getMaxBytes() {
        return maxBytes;
    }

    public void setMaxBytes(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    public int getMaxRedirects() {
        return maxRedirects;
    }

    public void setMaxRedirects(int maxRedirects) {
        this.maxRedirects = maxRedirects;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public boolean isAllowPrivateForTests() {
        return allowPrivateForTests;
    }

    public void setAllowPrivateForTests(boolean allowPrivateForTests) {
        this.allowPrivateForTests = allowPrivateForTests;
    }
}

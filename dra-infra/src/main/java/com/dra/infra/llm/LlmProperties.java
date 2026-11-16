package com.dra.infra.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dra.llm")
public class LlmProperties {

    private boolean enabled;
    private String baseUrl = "https://api.deepseek.com";
    private String apiKey = "";
    private String planModel = "deepseek-chat";
    private String extractModel = "deepseek-chat";
    private String claimModel = "deepseek-chat";
    private String verifyModel = "deepseek-reasoner";
    private double planTemperature = 0.2;
    private double extractTemperature = 0.1;
    private double verifyTemperature = 0.0;
    private int maxTokens = 4096;
    private int connectTimeoutSeconds = 10;
    private int readTimeoutSeconds = 90;
    /** Micros (1e-6 CNY) per 1M tokens; configure to match your provider pricing. */
    private long inputMicrosPerMillionTokens = 2_000_000;
    private long outputMicrosPerMillionTokens = 8_000_000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getPlanModel() {
        return planModel;
    }

    public void setPlanModel(String planModel) {
        this.planModel = planModel;
    }

    public String getExtractModel() {
        return extractModel;
    }

    public void setExtractModel(String extractModel) {
        this.extractModel = extractModel;
    }

    public String getVerifyModel() {
        return verifyModel;
    }

    public void setVerifyModel(String verifyModel) {
        this.verifyModel = verifyModel;
    }

    public String getClaimModel() {
        return claimModel;
    }

    public void setClaimModel(String claimModel) {
        this.claimModel = claimModel;
    }

    public double getPlanTemperature() {
        return planTemperature;
    }

    public void setPlanTemperature(double planTemperature) {
        this.planTemperature = planTemperature;
    }

    public double getExtractTemperature() {
        return extractTemperature;
    }

    public void setExtractTemperature(double extractTemperature) {
        this.extractTemperature = extractTemperature;
    }

    public double getVerifyTemperature() {
        return verifyTemperature;
    }

    public void setVerifyTemperature(double verifyTemperature) {
        this.verifyTemperature = verifyTemperature;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public int getReadTimeoutSeconds() {
        return readTimeoutSeconds;
    }

    public void setReadTimeoutSeconds(int readTimeoutSeconds) {
        this.readTimeoutSeconds = readTimeoutSeconds;
    }

    public long getInputMicrosPerMillionTokens() {
        return inputMicrosPerMillionTokens;
    }

    public void setInputMicrosPerMillionTokens(long inputMicrosPerMillionTokens) {
        this.inputMicrosPerMillionTokens = inputMicrosPerMillionTokens;
    }

    public long getOutputMicrosPerMillionTokens() {
        return outputMicrosPerMillionTokens;
    }

    public void setOutputMicrosPerMillionTokens(long outputMicrosPerMillionTokens) {
        this.outputMicrosPerMillionTokens = outputMicrosPerMillionTokens;
    }

    public long costMicros(long promptTokens, long completionTokens) {
        return (promptTokens * inputMicrosPerMillionTokens
                + completionTokens * outputMicrosPerMillionTokens) / 1_000_000L;
    }
}

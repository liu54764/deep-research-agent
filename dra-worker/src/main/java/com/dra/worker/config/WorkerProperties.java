package com.dra.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "dra.worker")
public class WorkerProperties {

    private List<String> roles = List.of("orchestration", "io", "llm");
    private int ioConcurrency = 50;
    private int llmConcurrency = 20;

    private long planSleepMs = 400;
    private long searchSleepMs = 300;
    private long fetchSleepMs = 400;
    private long extractSleepMs = 400;
    private long coverageSleepMs = 300;
    private long synthesizeSleepMs = 300;
    private long verifySleepMs = 200;
    private long reportSleepMs = 400;
    private double coverageRatio = 0.85;

    public List<String> getRoles() {
        return roles;
    }

    public void setRoles(List<String> roles) {
        this.roles = roles;
    }

    public int getIoConcurrency() {
        return ioConcurrency;
    }

    public void setIoConcurrency(int ioConcurrency) {
        this.ioConcurrency = ioConcurrency;
    }

    public int getLlmConcurrency() {
        return llmConcurrency;
    }

    public void setLlmConcurrency(int llmConcurrency) {
        this.llmConcurrency = llmConcurrency;
    }

    public long getPlanSleepMs() {
        return planSleepMs;
    }

    public void setPlanSleepMs(long planSleepMs) {
        this.planSleepMs = planSleepMs;
    }

    public long getSearchSleepMs() {
        return searchSleepMs;
    }

    public void setSearchSleepMs(long searchSleepMs) {
        this.searchSleepMs = searchSleepMs;
    }

    public long getFetchSleepMs() {
        return fetchSleepMs;
    }

    public void setFetchSleepMs(long fetchSleepMs) {
        this.fetchSleepMs = fetchSleepMs;
    }

    public long getExtractSleepMs() {
        return extractSleepMs;
    }

    public void setExtractSleepMs(long extractSleepMs) {
        this.extractSleepMs = extractSleepMs;
    }

    public long getCoverageSleepMs() {
        return coverageSleepMs;
    }

    public void setCoverageSleepMs(long coverageSleepMs) {
        this.coverageSleepMs = coverageSleepMs;
    }

    public long getSynthesizeSleepMs() {
        return synthesizeSleepMs;
    }

    public void setSynthesizeSleepMs(long synthesizeSleepMs) {
        this.synthesizeSleepMs = synthesizeSleepMs;
    }

    public long getVerifySleepMs() {
        return verifySleepMs;
    }

    public void setVerifySleepMs(long verifySleepMs) {
        this.verifySleepMs = verifySleepMs;
    }

    public long getReportSleepMs() {
        return reportSleepMs;
    }

    public void setReportSleepMs(long reportSleepMs) {
        this.reportSleepMs = reportSleepMs;
    }

    public double getCoverageRatio() {
        return coverageRatio;
    }

    public void setCoverageRatio(double coverageRatio) {
        this.coverageRatio = coverageRatio;
    }
}

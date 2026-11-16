package com.dra.eval.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** All CLI knobs live under {@code dra.eval.*}; short flags are normalized in the app entrypoint. */
@ConfigurationProperties(prefix = "dra.eval")
public class EvalProperties {

    private String mode = "self-test";
    private String apiBaseUrl = "http://localhost:8080";
    private String apiKey = "dev-key";
    private String dataset = "eval/dataset/questions.jsonl";
    private String split = "regression";
    private String depth = "QUICK";
    private int limit;
    private int parallel = 2;
    private long pollIntervalMs = 2000;
    private int taskTimeoutMinutes = 20;
    private double maxCostCny = 3.0;
    private String out = "eval/runs";
    private String baseline = "";
    private boolean freezeBaseline;
    private String rescoreFrom = "";
    private boolean thresholdsEnabled = true;
    private Thresholds thresholds = new Thresholds();
    private int confidenceMinSamples = 100;
    private double confidenceMaxEce = 0.15;
    private String labels = "";
    private String labelsDir = "eval/labels";
    private String cacheDir = "eval/cache/judge";
    private double kappaThreshold = 0.7;
    private int kappaSampleJ1 = 12;
    private int kappaSampleJ2 = 4;
    private UrlCheck urlCheck = new UrlCheck();
    private Judge judge = new Judge();

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getDataset() {
        return dataset;
    }

    public void setDataset(String dataset) {
        this.dataset = dataset;
    }

    public String getSplit() {
        return split;
    }

    public void setSplit(String split) {
        this.split = split;
    }

    public String getDepth() {
        return depth;
    }

    public void setDepth(String depth) {
        this.depth = depth;
    }

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }

    public int getParallel() {
        return parallel;
    }

    public void setParallel(int parallel) {
        this.parallel = parallel;
    }

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    public int getTaskTimeoutMinutes() {
        return taskTimeoutMinutes;
    }

    public void setTaskTimeoutMinutes(int taskTimeoutMinutes) {
        this.taskTimeoutMinutes = taskTimeoutMinutes;
    }

    public double getMaxCostCny() {
        return maxCostCny;
    }

    public void setMaxCostCny(double maxCostCny) {
        this.maxCostCny = maxCostCny;
    }

    public String getOut() {
        return out;
    }

    public void setOut(String out) {
        this.out = out;
    }

    public String getBaseline() {
        return baseline;
    }

    public void setBaseline(String baseline) {
        this.baseline = baseline;
    }

    public boolean isFreezeBaseline() {
        return freezeBaseline;
    }

    public void setFreezeBaseline(boolean freezeBaseline) {
        this.freezeBaseline = freezeBaseline;
    }

    public String getRescoreFrom() {
        return rescoreFrom;
    }

    public void setRescoreFrom(String rescoreFrom) {
        this.rescoreFrom = rescoreFrom;
    }

    public boolean isThresholdsEnabled() {
        return thresholdsEnabled;
    }

    public void setThresholdsEnabled(boolean thresholdsEnabled) {
        this.thresholdsEnabled = thresholdsEnabled;
    }

    public Thresholds getThresholds() {
        return thresholds;
    }

    public void setThresholds(Thresholds thresholds) {
        this.thresholds = thresholds;
    }

    public int getConfidenceMinSamples() {
        return confidenceMinSamples;
    }

    public void setConfidenceMinSamples(int confidenceMinSamples) {
        this.confidenceMinSamples = confidenceMinSamples;
    }

    public double getConfidenceMaxEce() {
        return confidenceMaxEce;
    }

    public void setConfidenceMaxEce(double confidenceMaxEce) {
        this.confidenceMaxEce = confidenceMaxEce;
    }

    public String getLabels() {
        return labels;
    }

    public void setLabels(String labels) {
        this.labels = labels;
    }

    public String getLabelsDir() {
        return labelsDir;
    }

    public void setLabelsDir(String labelsDir) {
        this.labelsDir = labelsDir;
    }

    public String getCacheDir() {
        return cacheDir;
    }

    public void setCacheDir(String cacheDir) {
        this.cacheDir = cacheDir;
    }

    public double getKappaThreshold() {
        return kappaThreshold;
    }

    public void setKappaThreshold(double kappaThreshold) {
        this.kappaThreshold = kappaThreshold;
    }

    public int getKappaSampleJ1() {
        return kappaSampleJ1;
    }

    public void setKappaSampleJ1(int kappaSampleJ1) {
        this.kappaSampleJ1 = kappaSampleJ1;
    }

    public int getKappaSampleJ2() {
        return kappaSampleJ2;
    }

    public void setKappaSampleJ2(int kappaSampleJ2) {
        this.kappaSampleJ2 = kappaSampleJ2;
    }

    public UrlCheck getUrlCheck() {
        return urlCheck;
    }

    public void setUrlCheck(UrlCheck urlCheck) {
        this.urlCheck = urlCheck;
    }

    public Judge getJudge() {
        return judge;
    }

    public void setJudge(Judge judge) {
        this.judge = judge;
    }

    /** Cost guard ceiling in micro-CNY (1 CNY = 1_000_000 micros). */
    public long maxCostMicros() {
        return Math.round(maxCostCny * 1_000_000d);
    }

    public static class UrlCheck {
        private boolean enabled = true;
        private int sampleLimit = 20;
        private int timeoutSeconds = 8;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getSampleLimit() {
            return sampleLimit;
        }

        public void setSampleLimit(int sampleLimit) {
            this.sampleLimit = sampleLimit;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }

    /** Judge config: an independent model endpoint, never the generation model. */
    public static class Judge {
        private boolean enabled;
        private String baseUrl = "https://api.deepseek.com";
        private String apiKey = "";
        private String model = "deepseek-reasoner";
        private double temperature;
        private int maxTokens = 4096;
        private int connectTimeoutSeconds = 10;
        private int readTimeoutSeconds = 90;
        /** Reasoner pricing (CNY per 1M tokens) by default; adjust to your provider. */
        private long inputMicrosPerMillionTokens = 4_000_000;
        private long outputMicrosPerMillionTokens = 16_000_000;

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

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
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
    }

    /** MVP acceptance thresholds from M0-scope.md §6. Applied only when the metric exists. */
    public static class Thresholds {
        private double minCar = 0.85;
        private double minHfr = 0.90;
        /** Explicit override (--min-gcr); when set it wins over the depth defaults. */
        private double minGcr = 0.80;
        private boolean minGcrExplicit;
        /**
         * Gold coverage scales with how much budget a depth hands the planner: QUICK asks
         * 3 sub-questions and fetches 5 sources, so 0.80 is not reachable on comparison or
         * multi-part questions, while DEEP does reach it (measured 1.000 on the same gold).
         */
        private double minGcrQuick = 0.60;
        private double minGcrStandard = 0.75;
        private double minGcrDeep = 0.80;
        private double maxContradictionRate = 0.03;
        private double minSuccessRate = 0.98;

        public double getMinCar() {
            return minCar;
        }

        public void setMinCar(double minCar) {
            this.minCar = minCar;
        }

        public double getMinHfr() {
            return minHfr;
        }

        public void setMinHfr(double minHfr) {
            this.minHfr = minHfr;
        }

        public double getMinGcr() {
            return minGcr;
        }

        public void setMinGcr(double minGcr) {
            this.minGcr = minGcr;
            this.minGcrExplicit = true;
        }

        public double getMinGcrQuick() {
            return minGcrQuick;
        }

        public void setMinGcrQuick(double minGcrQuick) {
            this.minGcrQuick = minGcrQuick;
        }

        public double getMinGcrStandard() {
            return minGcrStandard;
        }

        public void setMinGcrStandard(double minGcrStandard) {
            this.minGcrStandard = minGcrStandard;
        }

        public double getMinGcrDeep() {
            return minGcrDeep;
        }

        public void setMinGcrDeep(double minGcrDeep) {
            this.minGcrDeep = minGcrDeep;
        }

        /** GCR floor for the depth actually used by this run. */
        public double minGcrFor(String depth) {
            if (minGcrExplicit) {
                return minGcr;
            }
            String value = depth == null ? "" : depth.trim().toUpperCase(java.util.Locale.ROOT);
            return switch (value) {
                case "QUICK" -> minGcrQuick;
                case "STANDARD" -> minGcrStandard;
                default -> minGcrDeep;
            };
        }

        public double getMaxContradictionRate() {
            return maxContradictionRate;
        }

        public void setMaxContradictionRate(double maxContradictionRate) {
            this.maxContradictionRate = maxContradictionRate;
        }

        public double getMinSuccessRate() {
            return minSuccessRate;
        }

        public void setMinSuccessRate(double minSuccessRate) {
            this.minSuccessRate = minSuccessRate;
        }
    }
}

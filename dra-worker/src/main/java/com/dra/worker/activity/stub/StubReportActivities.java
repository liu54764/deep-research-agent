package com.dra.worker.activity.stub;

import com.dra.common.id.Ids;
import com.dra.contracts.activity.ResearchActivities;
import com.dra.contracts.dto.ReportInput;
import com.dra.contracts.dto.ReportRef;
import com.dra.contracts.dto.Usage;
import com.dra.domain.model.ReportBundle;
import com.dra.domain.port.ReportDataRepository;
import com.dra.domain.port.ReportRepository;
import com.dra.worker.config.WorkerProperties;
import com.dra.worker.report.ReportRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;

/**
 * Stub mode still produces a real, deterministic report: claims that passed the stub
 * verification path are rendered with numbered sources and run statistics. Only the prose
 * is missing (no model call).
 */
@Component
@ConditionalOnProperty(prefix = "dra.llm", name = "enabled", havingValue = "false", matchIfMissing = true)
public class StubReportActivities implements ResearchActivities.Report {

    private final ReportDataRepository reportData;
    private final ReportRepository reportRepository;
    private final WorkerProperties properties;
    private final ObjectMapper objectMapper;
    private final ReportRenderer renderer = new ReportRenderer();

    public StubReportActivities(ReportDataRepository reportData, ReportRepository reportRepository,
                                WorkerProperties properties, ObjectMapper objectMapper) {
        this.reportData = reportData;
        this.reportRepository = reportRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public ReportRef generate(ReportInput input) {
        StubSupport.sleep(properties.getReportSleepMs());
        ReportBundle bundle = reportData.load(input.taskId(), input.stopReason(), input.warnings());
        ReportRenderer.Rendered rendered = renderer.render(bundle, "", LocalDate.now().toString(),
                Map.of("depth", String.valueOf(bundle.depth()), "mode", "stub"));
        String metaJson;
        try {
            metaJson = objectMapper.writeValueAsString(rendered.document());
        } catch (Exception e) {
            metaJson = "{}";
        }
        int version = reportRepository.findLatest(input.taskId())
                .map(row -> row.version() + 1)
                .orElse(1);
        var row = reportRepository.save(Ids.ulid(), input.taskId(), version,
                rendered.markdown(), metaJson);
        return new ReportRef(row.id(), row.version(), row.markdown(), Usage.tokens(800, 400));
    }
}

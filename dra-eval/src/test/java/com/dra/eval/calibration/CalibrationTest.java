package com.dra.eval.calibration;

import com.dra.eval.judge.LabelItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalibrationTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void cohenKappaMatchesKnownExample() {
        List<Calibration.Label> labels = new ArrayList<>();
        labels.addAll(repeat("SUPPORT", "SUPPORT", 10));
        labels.addAll(repeat("UNRELATED", "UNRELATED", 5));
        labels.addAll(repeat("SUPPORT", "UNRELATED", 3));
        labels.addAll(repeat("UNRELATED", "SUPPORT", 2));

        Calibration.Report report = Calibration.compute(labels);

        assertEquals("LABELED", report.status());
        assertEquals(20, report.labeledTotal());
        Calibration.KindResult kind = report.byKind().get("J1");
        assertEquals(15, kind.agreement());
        assertEquals(0.75, kind.observed(), 1e-9);
        assertEquals(0.53, kind.expected(), 1e-9);
        assertEquals(0.468085, kind.kappa(), 1e-5);
        assertEquals(kind.kappa(), report.minKappa(), 1e-9);
    }

    @Test
    void unlabeledFileIsReportedAsUncalibrated() {
        List<Calibration.Label> labels = List.of(
                new Calibration.Label("J1", "q1", "c1:[1]", "p", "SUPPORT", null));

        Calibration.Report report = Calibration.compute(labels);

        assertEquals("UNLABELED", report.status());
        assertEquals(0, report.labeledTotal());
        assertFalse(report.labeled());
    }

    @Test
    void perfectAgreementGivesKappaOne() {
        Calibration.Report report = Calibration.compute(repeat("SUPPORT", "SUPPORT", 6));

        assertEquals(1.0, report.byKind().get("J1").kappa(), 1e-9);
    }

    @Test
    void kindsAreScoredSeparatelyAndGateUsesTheWorst() {
        List<Calibration.Label> labels = new ArrayList<>(repeat("SUPPORT", "SUPPORT", 5));
        labels.addAll(List.of(
                new Calibration.Label("J2", "q1", "kp1", "p", "COVERED", "NOT_COVERED"),
                new Calibration.Label("J2", "q1", "kp2", "p", "NOT_COVERED", "NOT_COVERED")));

        Calibration.Report report = Calibration.compute(labels);

        assertEquals(1.0, report.byKind().get("J1").kappa(), 1e-9);
        assertEquals(0.0, report.byKind().get("J2").kappa(), 1e-9);
        assertEquals(0.0, report.minKappa(), 1e-9);
    }

    @Test
    void templateIsWrittenOnceAndReadBack(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("labels.jsonl");
        List<LabelItem> items = List.of(
                new LabelItem("J1", "q1", "c1:[1]", "prompt", "SUPPORT", null),
                new LabelItem("J2", "q1", "kp1", "prompt", "COVERED", null));

        assertTrue(Calibration.writeTemplate(file, items, mapper));
        assertFalse(Calibration.writeTemplate(file, items, mapper), "must not overwrite human edits");

        List<Calibration.Label> read = Calibration.read(file, mapper);
        assertEquals(2, read.size());
        assertEquals("SUPPORT", read.get(0).judgeVerdict());
        assertEquals(null, read.get(0).humanVerdict());

        Files.writeString(file, "{\"kind\":\"J1\",\"questionId\":\"q1\",\"subjectId\":\"c1:[1]\","
                + "\"prompt\":\"p\",\"judgeVerdict\":\"SUPPORT\",\"humanVerdict\":\"SUPPORT\"}\n",
                StandardCharsets.UTF_8);
        Calibration.Report report = Calibration.compute(Calibration.read(file, mapper));
        assertEquals("LABELED", report.status());
        assertEquals(1.0, report.minKappa(), 1e-9);
    }

    private static List<Calibration.Label> repeat(String judge, String human, int times) {
        List<Calibration.Label> labels = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            labels.add(new Calibration.Label("J1", "q" + i, "c" + i + ":[1]", "p", judge, human));
        }
        return labels;
    }
}

package com.dra.api.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record SubmitTaskRequest(
        @NotBlank @Size(max = 4000) String question,
        @Pattern(regexp = "(?i)QUICK|STANDARD|DEEP", message = "depth must be QUICK, STANDARD or DEEP")
        String depth,
        @Min(1) @Max(180) Integer deadlineMinutes,
        @Min(1000) Long budgetTokens,
        @Min(0) Long budgetCostMicros,
        @Size(max = 16) String language,
        List<String> allowedDomains,
        List<String> excludedDomains) {
}

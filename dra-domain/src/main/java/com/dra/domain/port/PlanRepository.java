package com.dra.domain.port;

import com.dra.contracts.dto.SubQuestion;

import java.util.List;

public interface PlanRepository {

    void savePlan(String planId, String taskId, int version, List<SubQuestion> subQuestions,
                  String modelProfile, String promptVersion);

    void saveSubQuestions(String taskId, int planVersion, List<SubQuestion> subQuestions);
}

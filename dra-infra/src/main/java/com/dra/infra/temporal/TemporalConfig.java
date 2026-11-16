package com.dra.infra.temporal;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TemporalConfig {

    @Bean(destroyMethod = "shutdown")
    public WorkflowServiceStubs workflowServiceStubs(
            @Value("${dra.temporal.target:127.0.0.1:7233}") String target) {
        return WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder().setTarget(target).build());
    }

    @Bean
    public WorkflowClient workflowClient(
            WorkflowServiceStubs serviceStubs,
            @Value("${dra.temporal.namespace:default}") String namespace) {
        return WorkflowClient.newInstance(serviceStubs,
                WorkflowClientOptions.newBuilder().setNamespace(namespace).build());
    }
}

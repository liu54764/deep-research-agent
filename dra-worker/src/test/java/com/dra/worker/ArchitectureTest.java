package com.dra.worker;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {

    private final JavaClasses classes = new ClassFileImporter().importPackages("com.dra");

    @Test
    void workflowMustNotDependOnInfraOrSpring() {
        noClasses().that().resideInAPackage("..worker.workflow..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.dra.infra..", "com.dra.api..", "org.springframework..")
                .check(classes);
    }

    @Test
    void contractsMustStayFrameworkFree() {
        noClasses().that().resideInAPackage("com.dra.contracts..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "com.dra.infra..", "com.dra.domain..", "com.dra.worker..")
                .check(classes);
    }

    @Test
    void domainMustNotDependOnInfraOrSpring() {
        noClasses().that().resideInAPackage("com.dra.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.dra.infra..", "com.dra.api..", "org.springframework..")
                .check(classes);
    }
}

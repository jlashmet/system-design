package com.systemdesign.chatgpt.conversation.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ConversationArchitectureTest {
    private JavaClasses classes;

    @Test
    void domainDoesNotDependOnOuterLayers() {
        given();
        whenClassesAreImported();
        thenExpectDomainIndependence();
    }

    @Test
    void applicationDoesNotDependOnApiOrInfrastructure() {
        given();
        whenClassesAreImported();
        thenExpectApplicationIndependence();
    }

    private void given() {
    }

    private void whenClassesAreImported() {
        classes = new ClassFileImporter().importPackages("com.systemdesign.chatgpt.conversation");
    }

    private void thenExpectDomainIndependence() {
        noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage("..application..", "..api..", "..infrastructure..")
                .check(classes);
    }

    private void thenExpectApplicationIndependence() {
        noClasses().that().resideInAPackage("..application..")
                .should().dependOnClassesThat().resideInAnyPackage("..api..", "..infrastructure..")
                .check(classes);
    }
}

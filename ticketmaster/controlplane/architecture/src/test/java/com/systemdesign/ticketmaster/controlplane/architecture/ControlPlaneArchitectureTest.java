package com.systemdesign.ticketmaster.controlplane.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.systemdesign.ticketmaster.controlplane")
class ControlPlaneArchitectureTest {

    @ArchTest
    static final ArchRule domain_does_not_depend_outward = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..api..", "..application..", "..infrastructure..", "..bootstrap..",
                    "org.springframework..", "software.amazon..");

    @ArchTest
    static final ArchRule application_depends_only_inward = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..api..", "..infrastructure..", "..bootstrap..",
                    "org.springframework..", "software.amazon..");

    @ArchTest
    static final ArchRule input_does_not_depend_on_output_or_bootstrap = noClasses()
            .that().resideInAPackage("..infrastructure.input..")
            .should().dependOnClassesThat().resideInAnyPackage("..infrastructure.output..", "..bootstrap..");

    @ArchTest
    static final ArchRule output_does_not_depend_on_api_application_input_or_bootstrap = noClasses()
            .that().resideInAPackage("..infrastructure.output..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..api..", "..application..", "..infrastructure.input..", "..bootstrap..");

    @ArchTest
    static final ArchRule bounded_context_does_not_depend_on_other_contexts = noClasses()
            .that().resideInAPackage("com.systemdesign.ticketmaster.controlplane..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.systemdesign.ticketmaster.booking..",
                    "com.systemdesign.ticketmaster.search..",
                    "com.systemdesign.ticketmaster.events..");
}

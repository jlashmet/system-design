package com.systemdesign.ticketmaster.events.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.systemdesign.ticketmaster.events")
class EventsArchitectureTest {

    @ArchTest
    static final ArchRule domain_does_not_depend_outward = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..application..", "..infrastructure..", "org.springframework..",
                    "software.amazon..", "org.opensearch..");

    @ArchTest
    static final ArchRule application_does_not_depend_on_infrastructure = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..infrastructure..", "org.springframework..", "software.amazon..", "org.opensearch..");

    @ArchTest
    static final ArchRule input_does_not_depend_on_output = noClasses()
            .that().resideInAPackage("..infrastructure.input..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure.output..");

    @ArchTest
    static final ArchRule output_does_not_depend_on_application_or_input = noClasses()
            .that().resideInAPackage("..infrastructure.output..")
            .should().dependOnClassesThat().resideInAnyPackage("..application..", "..infrastructure.input..");

    @ArchTest
    static final ArchRule bounded_context_does_not_depend_on_other_contexts = noClasses()
            .that().resideInAPackage("com.systemdesign.ticketmaster.events..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.systemdesign.ticketmaster.booking..", "com.systemdesign.ticketmaster.search..");
}

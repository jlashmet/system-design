package com.systemdesign.ticketmaster.booking.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class BookingModuleBoundaryTest {

    @Test
    void businessSubdomainsDependOnForeignSubdomainsOnlyThroughApiArtifacts() throws Exception {
        assertNoForeignDomains("../admission/domain/pom.xml",
                "booking-reservation-domain", "booking-checkout-domain", "booking-payment-domain");
        assertNoForeignDomains("../admission/application/pom.xml",
                "booking-reservation-domain", "booking-checkout-domain", "booking-payment-domain");

        assertNoForeignDomains("../reservation/domain/pom.xml",
                "booking-admission-domain", "booking-checkout-domain", "booking-payment-domain");
        assertNoForeignDomains("../reservation/application/pom.xml",
                "booking-admission-domain", "booking-checkout-domain", "booking-payment-domain");

        assertNoForeignDomains("../checkout/domain/pom.xml",
                "booking-admission-domain", "booking-reservation-domain", "booking-payment-domain");
        assertNoForeignDomains("../checkout/application/pom.xml",
                "booking-admission-domain", "booking-reservation-domain", "booking-payment-domain");

        assertContains("../reservation/api/pom.xml", "booking-admission-api");
        assertContains("../reservation/application/pom.xml", "booking-admission-api");
        assertContains("../checkout/domain/pom.xml", "booking-admission-api");
        assertContains("../checkout/domain/pom.xml", "booking-reservation-api");
        assertContains("../checkout/application/pom.xml", "booking-admission-api");
        assertContains("../checkout/application/pom.xml", "booking-reservation-api");
        assertContains("../checkout/application/pom.xml", "booking-payment-api");
        assertContains("../checkout/infrastructure/pom.xml", "booking-checkout-domain");
        assertContains("../payment/api/pom.xml", "booking-admission-api");
        assertContains("../payment/api/pom.xml", "booking-reservation-api");
        assertContains("../payment/api/pom.xml", "booking-checkout-api");
        assertContains("../payment/infrastructure/pom.xml", "booking-payment-api");

        String checkoutAggregator = pom("../checkout/pom.xml");
        assertTrue(checkoutAggregator.contains("<module>infrastructure</module>"));

        String paymentAggregator = pom("../payment/pom.xml");
        assertTrue(paymentAggregator.contains("<module>api</module>"));
        assertTrue(paymentAggregator.contains("<module>infrastructure</module>"));
        assertFalse(paymentAggregator.contains("<module>domain</module>"));
        assertFalse(paymentAggregator.contains("<module>application</module>"));

        String infrastructureAggregator = pom("../infrastructure/pom.xml");
        assertFalse(infrastructureAggregator.contains("<module>output</module>"));
    }

    @Test
    void bookingHasNoSharedKernelArtifact() throws Exception {
        Path bookingDirectory = moduleDirectory().getParent();
        assertFalse(Files.exists(bookingDirectory.resolve("shared")),
                "Booking must not reintroduce an ownerless shared module");
        assertFalse(Files.readString(bookingDirectory.resolve("pom.xml")).contains("<module>shared</module>"),
                "Booking aggregator must not include a shared module");

        for (String relativePom : List.of(
                "../admission/api/pom.xml",
                "../admission/domain/pom.xml",
                "../admission/application/pom.xml",
                "../reservation/api/pom.xml",
                "../reservation/domain/pom.xml",
                "../reservation/application/pom.xml",
                "../checkout/api/pom.xml",
                "../checkout/domain/pom.xml",
                "../checkout/application/pom.xml",
                "../checkout/infrastructure/pom.xml",
                "../payment/api/pom.xml",
                "../payment/infrastructure/pom.xml",
                "../infrastructure/input/pom.xml",
                "../tests/domain/pom.xml",
                "../tests/application/pom.xml",
                "../bootstrap/pom.xml",
                "pom.xml")) {
            assertFalse(pom(relativePom).contains("booking-shared-domain"),
                    relativePom + " must not depend on booking-shared-domain");
        }
    }

    private static void assertNoForeignDomains(String relativePom, String... artifactIds) throws Exception {
        String pom = pom(relativePom);
        for (String artifactId : artifactIds) {
            assertFalse(pom.contains("<artifactId>" + artifactId + "</artifactId>"),
                    relativePom + " must not depend on " + artifactId);
        }
    }

    private static void assertContains(String relativePom, String artifactId) throws Exception {
        assertTrue(pom(relativePom).contains("<artifactId>" + artifactId + "</artifactId>"),
                relativePom + " must depend on " + artifactId);
    }

    private static String pom(String relativePom) throws Exception {
        return Files.readString(moduleDirectory().resolve(relativePom).normalize());
    }

    private static Path moduleDirectory() throws URISyntaxException {
        Path testClasses = Path.of(BookingModuleBoundaryTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        return testClasses.getParent().getParent();
    }
}

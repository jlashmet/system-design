package com.systemdesign.ticketmaster.booking.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URISyntaxException;
import java.nio.file.Path;
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

        assertContains("../reservation/application/pom.xml", "booking-admission-api");
        assertContains("../checkout/domain/pom.xml", "booking-reservation-api");
        assertContains("../checkout/application/pom.xml", "booking-reservation-api");
        assertContains("../checkout/application/pom.xml", "booking-payment-api");

        String paymentAggregator = pom("../payment/pom.xml");
        assertTrue(paymentAggregator.contains("<module>api</module>"));
        assertFalse(paymentAggregator.contains("<module>domain</module>"));
        assertFalse(paymentAggregator.contains("<module>application</module>"));
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
        return java.nio.file.Files.readString(moduleDirectory().resolve(relativePom).normalize());
    }

    private static Path moduleDirectory() throws URISyntaxException {
        Path testClasses = Path.of(BookingModuleBoundaryTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        return testClasses.getParent().getParent();
    }
}

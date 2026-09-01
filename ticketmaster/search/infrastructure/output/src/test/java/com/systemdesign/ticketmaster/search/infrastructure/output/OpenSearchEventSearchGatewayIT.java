package com.systemdesign.ticketmaster.search.infrastructure.output;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.systemdesign.ticketmaster.search.domain.SearchEvent;
import com.systemdesign.ticketmaster.search.domain.SearchPage;
import com.systemdesign.ticketmaster.search.domain.SearchQuery;
import com.systemdesign.ticketmaster.search.domain.SearchUnavailableException;
import io.floci.testcontainers.FlociContainer;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.apache.hc.core5.http.HttpHost;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.HealthStatus;
import org.opensearch.client.opensearch._types.Refresh;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5Transport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;

@Testcontainers
class OpenSearchEventSearchGatewayIT {
    private static final String DOMAIN_NAME = "ticketmaster-search";
    private static final String INDEX_NAME = "events";
    private static final Instant OCTOBER_1 = Instant.parse("2026-10-01T00:00:00Z");

    // testcontainers-floci 2.15.0 was released against Floci 1.7.0 but its default
    // constructor follows floci/floci:latest. Pin the emulator so a later breaking
    // Floci release cannot silently change this integration fixture underneath us.
    @Container
    static final FlociContainer FLOCI = new FlociContainer("floci/floci:1.7.0")
            .withDedicatedNetwork()
            .withOpenSearchConfig(config -> config.enabled(true).mock(false));

    private static software.amazon.awssdk.services.opensearch.OpenSearchClient controlPlane;
    private static ApacheHttpClient5Transport transport;
    private static OpenSearchClient dataClient;
    private static OpenSearchEventSearchGateway gateway;
    private static OpenSearchEventSearchIndex index;

    private SearchPage firstPage;
    private SearchPage secondPage;
    private Throwable thrown;

    @BeforeAll
    static void setUpOpenSearch() {
        controlPlane = software.amazon.awssdk.services.opensearch.OpenSearchClient.builder()
                .endpointOverride(URI.create(FLOCI.getEndpoint()))
                .region(Region.of(FLOCI.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(FLOCI.getAccessKey(), FLOCI.getSecretKey())))
                .httpClientBuilder(Apache5HttpClient.builder().socketTimeout(Duration.ofMinutes(5)))
                .build();
        controlPlane.createDomain(request -> request.domainName(DOMAIN_NAME));

        HttpHost host = new HttpHost("http", FLOCI.getHost(), FLOCI.getOpenSearchConfig().getProxyBasePort());
        transport = ApacheHttpClient5TransportBuilder.builder(host)
                .setMapper(new JacksonJsonpMapper())
                .build();
        dataClient = new OpenSearchClient(transport);
        await().atMost(Duration.ofMinutes(2))
                .pollInterval(Duration.ofSeconds(2))
                .ignoreExceptions()
                .untilAsserted(() -> assertThat(dataClient.cluster()
                        .health(health -> health.waitForStatus(HealthStatus.Yellow).timeout(timeout -> timeout.time("5s")))
                        .status()).isIn(HealthStatus.Green, HealthStatus.Yellow));
        gateway = new OpenSearchEventSearchGateway(dataClient, INDEX_NAME);
        index = new OpenSearchEventSearchIndex(dataClient, INDEX_NAME);
    }

    @AfterAll
    static void tearDownOpenSearch() throws Exception {
        if (dataClient != null && dataClient.indices().exists(exists -> exists.index(INDEX_NAME)).value()) {
            dataClient.indices().delete(delete -> delete.index(INDEX_NAME));
        }
        if (controlPlane != null) {
            controlPlane.deleteDomain(request -> request.domainName(DOMAIN_NAME));
            controlPlane.close();
        }
        if (transport != null) transport.close();
    }

    @Test
    void indexesEventForSearch() {
        givenIndexedEvent();
        whenSearch(new SearchQuery("National", "Los Angeles", OCTOBER_1,
                Instant.parse("2026-11-01T00:00:00Z"), "", 10));
        thenExpectFirstPage("event-42");
    }

    @Test
    void deletesEventFromSearch() {
        givenIndexedEvent();
        whenIndexedEventIsDeletedAndSearched();
        thenExpectFirstPage();
    }

    @Test
    void backendResponseFailureIsSearchUnavailable() {
        givenMissingIndex();
        whenMissingIndexIsSearched();
        thenExpectSearchUnavailable();
    }

    @Test
    void filtersByTextCityAndDateRange() {
        givenEvents(
                event("1", "Taylor Swift", "SoFi Stadium", "Los Angeles", "2026-10-10T03:00:00Z", "CONCERT"),
                event("2", "Taylor Swift", "Levi's Stadium", "Santa Clara", "2026-10-12T03:00:00Z", "CONCERT"),
                event("3", "Taylor Swift", "SoFi Stadium", "Los Angeles", "2027-01-10T03:00:00Z", "CONCERT"));
        whenSearch(new SearchQuery("Taylor", "Los Angeles", OCTOBER_1,
                Instant.parse("2026-11-01T00:00:00Z"), "", 10));
        thenExpectFirstPage("1");
    }

    @Test
    void cursorPaginatesInStableEventTimeOrder() {
        givenEvents(
                event("event-a", "Alpha", "Venue", "Los Angeles", "2026-10-10T03:00:00Z", "CONCERT"),
                event("event-b", "Beta", "Venue", "Los Angeles", "2026-10-10T03:00:00Z", "CONCERT"),
                event("event-c", "Gamma", "Venue", "Los Angeles", "2026-10-11T03:00:00Z", "CONCERT"));
        whenSearchTwoPages(new SearchQuery("", "", null, null, "", 2));
        thenExpectPages(List.of("event-a", "event-b"), List.of("event-c"));
    }

    private void givenIndexedEvent() {
        try {
            recreateIndex();
            index.upsert(new SearchEvent(
                    "event-42",
                    "The National",
                    "Hollywood Bowl",
                    "Los Angeles",
                    Instant.parse("2026-10-20T03:00:00Z"),
                    "CONCERT"));
            dataClient.indices().refresh(refresh -> refresh.index(INDEX_NAME));
            resetResults();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void givenMissingIndex() {
        try {
            if (dataClient.indices().exists(exists -> exists.index(INDEX_NAME)).value()) {
                dataClient.indices().delete(delete -> delete.index(INDEX_NAME));
            }
            resetResults();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void givenEvents(SearchEventDocument... documents) {
        try {
            recreateIndex();
            for (SearchEventDocument document : documents) {
                dataClient.index(indexRequest -> indexRequest
                        .index(INDEX_NAME)
                        .id(document.eventId())
                        .document(document)
                        .refresh(Refresh.True));
            }
            resetResults();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void recreateIndex() throws Exception {
        if (dataClient.indices().exists(exists -> exists.index(INDEX_NAME)).value()) {
            dataClient.indices().delete(delete -> delete.index(INDEX_NAME));
        }
        dataClient.indices().create(create -> create.index(INDEX_NAME)
                .mappings(mappings -> mappings
                        .properties("eventId", property -> property.keyword(keyword -> keyword))
                        .properties("name", property -> property.text(text -> text))
                        .properties("venue", property -> property.text(text -> text))
                        .properties("city", property -> property.keyword(keyword -> keyword))
                        .properties("startsAtEpochMillis", property -> property.long_(longNumber -> longNumber))
                        .properties("category", property -> property.text(text -> text))));
    }

    private void whenSearch(SearchQuery query) {
        firstPage = gateway.search(query);
    }

    private void whenIndexedEventIsDeletedAndSearched() {
        try {
            index.delete("event-42");
            dataClient.indices().refresh(refresh -> refresh.index(INDEX_NAME));
            firstPage = gateway.search(new SearchQuery("National", "Los Angeles", OCTOBER_1,
                    Instant.parse("2026-11-01T00:00:00Z"), "", 10));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void whenMissingIndexIsSearched() {
        try {
            gateway.search(new SearchQuery("", "", null, null, "", 10));
        } catch (Throwable error) {
            thrown = error;
        }
    }

    private void whenSearchTwoPages(SearchQuery query) {
        firstPage = gateway.search(query);
        secondPage = gateway.search(new SearchQuery(query.text(), query.city(), query.startsAfter(), query.startsBefore(),
                firstPage.nextCursor(), query.limit()));
    }

    private void thenExpectFirstPage(String... eventIds) {
        assertThat(firstPage.events()).extracting(SearchEvent::eventId).containsExactly(eventIds);
        assertThat(firstPage.nextCursor()).isEmpty();
    }

    private void thenExpectSearchUnavailable() {
        assertThat(thrown).isInstanceOf(SearchUnavailableException.class);
    }

    private void thenExpectPages(List<String> firstIds, List<String> secondIds) {
        assertThat(firstPage.events()).extracting(SearchEvent::eventId).containsExactlyElementsOf(firstIds);
        assertThat(firstPage.nextCursor()).isNotBlank();
        assertThat(secondPage.events()).extracting(SearchEvent::eventId).containsExactlyElementsOf(secondIds);
        assertThat(secondPage.nextCursor()).isEmpty();
    }

    private void resetResults() {
        firstPage = null;
        secondPage = null;
        thrown = null;
    }

    private static SearchEventDocument event(String eventId, String name, String venue, String city,
                                             String startsAt, String category) {
        return new SearchEventDocument(eventId, name, venue, city, Instant.parse(startsAt).toEpochMilli(), category);
    }
}

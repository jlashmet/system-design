package com.systemdesign.ticketmaster.search.bootstrap;

import com.systemdesign.ticketmaster.search.application.DeleteSearchEventHandler;
import com.systemdesign.ticketmaster.search.application.IndexSearchEventHandler;
import com.systemdesign.ticketmaster.search.application.SearchEventsHandler;
import com.systemdesign.ticketmaster.search.domain.EventSearchGateway;
import com.systemdesign.ticketmaster.search.domain.EventSearchIndex;
import com.systemdesign.ticketmaster.search.infrastructure.input.EventSearchProjectionConsumer;
import com.systemdesign.ticketmaster.search.infrastructure.output.OpenSearchEventSearchGateway;
import com.systemdesign.ticketmaster.search.infrastructure.output.OpenSearchEventSearchIndex;
import java.net.URI;
import java.time.Duration;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.util.Timeout;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.aws.AwsSdk2Transport;
import org.opensearch.client.transport.aws.AwsSdk2TransportOptions;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;

@SpringBootApplication(scanBasePackages = "com.systemdesign.ticketmaster.search.infrastructure.input")
public class SearchServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(SearchServiceApplication.class, args);
    }

    @Bean(destroyMethod = "close")
    OpenSearchTransport openSearchTransport(
            @Value("${ticketmaster.search.endpoint:http://localhost:9200}") String endpoint,
            @Value("${ticketmaster.search.connect-timeout-ms:200}") long connectTimeoutMillis,
            @Value("${ticketmaster.search.response-timeout-ms:450}") long responseTimeoutMillis,
            @Value("${ticketmaster.search.aws-signing-enabled:false}") boolean awsSigningEnabled,
            @Value("${ticketmaster.search.aws-signing-service:es}") String signingService,
            @Value("${ticketmaster.aws.region:us-west-2}") String region) {
        long connectTimeout = requirePositiveMillis(connectTimeoutMillis, "connectTimeoutMillis");
        long responseTimeout = requirePositiveMillis(responseTimeoutMillis, "responseTimeoutMillis");
        URI uri = endpoint.contains("://") ? URI.create(endpoint) : URI.create("https://" + endpoint);
        String hostName = requireNonBlank(uri.getHost(), "OpenSearch endpoint host");

        if (awsSigningEnabled) {
            SdkHttpClient httpClient = ApacheHttpClient.builder()
                    .connectionTimeout(Duration.ofMillis(connectTimeout))
                    .socketTimeout(Duration.ofMillis(responseTimeout))
                    .build();
            return new AwsSdk2Transport(
                    httpClient,
                    hostName,
                    requireNonBlank(signingService, "signingService"),
                    Region.of(requireNonBlank(region, "region")),
                    AwsSdk2TransportOptions.builder().build());
        }

        int port = uri.getPort() >= 0 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
        HttpHost host = new HttpHost(uri.getScheme(), hostName, port);
        return ApacheHttpClient5TransportBuilder.builder(host)
                .setMapper(new JacksonJsonpMapper())
                .setConnectionConfigCallback(config -> config
                        .setConnectTimeout(Timeout.ofMilliseconds(connectTimeout))
                        .setSocketTimeout(Timeout.ofMilliseconds(responseTimeout)))
                .build();
    }

    @Bean
    OpenSearchClient openSearchClient(OpenSearchTransport transport) {
        return new OpenSearchClient(transport);
    }

    @Bean
    EventSearchGateway eventSearchGateway(
            OpenSearchClient openSearchClient,
            @Value("${ticketmaster.search.index-name:events}") String indexName) {
        return new OpenSearchEventSearchGateway(openSearchClient, indexName);
    }

    @Bean
    EventSearchIndex eventSearchIndex(
            OpenSearchClient openSearchClient,
            @Value("${ticketmaster.search.index-name:events}") String indexName) {
        return new OpenSearchEventSearchIndex(openSearchClient, indexName);
    }

    @Bean
    SearchEventsHandler searchEventsHandler(EventSearchGateway eventSearchGateway) {
        return new SearchEventsHandler(eventSearchGateway);
    }

    @Bean
    IndexSearchEventHandler indexSearchEventHandler(EventSearchIndex eventSearchIndex) {
        return new IndexSearchEventHandler(eventSearchIndex);
    }

    @Bean
    DeleteSearchEventHandler deleteSearchEventHandler(EventSearchIndex eventSearchIndex) {
        return new DeleteSearchEventHandler(eventSearchIndex);
    }

    @Bean
    EventSearchProjectionConsumer eventSearchProjectionConsumer(
            IndexSearchEventHandler indexHandler,
            DeleteSearchEventHandler deleteHandler) {
        return new EventSearchProjectionConsumer(indexHandler, deleteHandler);
    }

    private static long requirePositiveMillis(long value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}

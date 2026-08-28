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
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.util.Timeout;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5Transport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(scanBasePackages = "com.systemdesign.ticketmaster.search.infrastructure.input")
public class SearchServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(SearchServiceApplication.class, args);
    }

    @Bean(destroyMethod = "close")
    ApacheHttpClient5Transport openSearchTransport(
            @Value("${ticketmaster.search.endpoint:http://localhost:9200}") String endpoint,
            @Value("${ticketmaster.search.connect-timeout-ms:200}") long connectTimeoutMillis,
            @Value("${ticketmaster.search.response-timeout-ms:450}") long responseTimeoutMillis) {
        long connectTimeout = requirePositiveMillis(connectTimeoutMillis, "connectTimeoutMillis");
        long responseTimeout = requirePositiveMillis(responseTimeoutMillis, "responseTimeoutMillis");
        URI uri = URI.create(endpoint);
        int port = uri.getPort() >= 0 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
        HttpHost host = new HttpHost(uri.getScheme(), uri.getHost(), port);
        return ApacheHttpClient5TransportBuilder.builder(host)
                .setMapper(new JacksonJsonpMapper())
                .setConnectionConfigCallback(config -> config
                        .setConnectTimeout(Timeout.ofMilliseconds(connectTimeout))
                        .setSocketTimeout(Timeout.ofMilliseconds(responseTimeout)))
                .build();
    }

    @Bean
    OpenSearchClient openSearchClient(ApacheHttpClient5Transport transport) {
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
}

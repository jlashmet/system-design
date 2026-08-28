package com.systemdesign.ticketmaster.search.bootstrap;

import java.util.Objects;
import org.opensearch.client.transport.aws.AwsSdk2Transport;
import org.opensearch.client.transport.aws.AwsSdk2TransportOptions;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.regions.Region;

/**
 * AwsSdk2Transport does not close a caller-supplied AWS HTTP client. This wrapper makes that
 * ownership explicit for long-running Search service instances so Spring shutdown releases the
 * client's connection pool and idle-reaper registration.
 */
final class ManagedAwsSdk2Transport extends AwsSdk2Transport {
    private final SdkHttpClient httpClient;

    ManagedAwsSdk2Transport(
            SdkHttpClient httpClient,
            String host,
            String signingServiceName,
            Region signingRegion,
            AwsSdk2TransportOptions options) {
        super(
                Objects.requireNonNull(httpClient, "httpClient"),
                Objects.requireNonNull(host, "host"),
                Objects.requireNonNull(signingServiceName, "signingServiceName"),
                Objects.requireNonNull(signingRegion, "signingRegion"),
                Objects.requireNonNull(options, "options"));
        this.httpClient = httpClient;
    }

    @Override
    public void close() {
        try {
            super.close();
        } finally {
            httpClient.close();
        }
    }
}

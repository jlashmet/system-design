package com.systemdesign.ticketmaster.search.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.opensearch.client.transport.aws.AwsSdk2TransportOptions;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.regions.Region;

class ManagedAwsSdk2TransportTest {
    private CloseTrackingHttpClient httpClient;
    private ManagedAwsSdk2Transport transport;

    @Test
    void closesCallerOwnedAwsHttpClient() {
        given();
        whenTransportClosed();
        thenExpect(true);
    }

    private void given() {
        httpClient = new CloseTrackingHttpClient();
        transport = new ManagedAwsSdk2Transport(
                httpClient,
                "search.example.us-west-2.es.amazonaws.com",
                "es",
                Region.US_WEST_2,
                AwsSdk2TransportOptions.builder().build());
    }

    private void whenTransportClosed() {
        transport.close();
    }

    private void thenExpect(boolean expectedClosed) {
        assertThat(httpClient.closed).isEqualTo(expectedClosed);
    }

    private static final class CloseTrackingHttpClient implements SdkHttpClient {
        private boolean closed;

        @Override
        public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
            throw new UnsupportedOperationException("not used by lifecycle test");
        }

        @Override
        public String clientName() {
            return "close-tracking";
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}

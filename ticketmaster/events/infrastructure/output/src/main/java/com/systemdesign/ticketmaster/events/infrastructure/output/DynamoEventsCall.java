package com.systemdesign.ticketmaster.events.infrastructure.output;

import com.systemdesign.ticketmaster.events.infrastructure.common.EventsStorageUnavailableException;
import java.util.Objects;
import java.util.function.Supplier;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;

final class DynamoEventsCall {
    private DynamoEventsCall() {
    }

    static <T> T execute(String operation, Supplier<T> call) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(call, "call");
        try {
            return call.get();
        } catch (DynamoDbException | SdkClientException unavailable) {
            throw new EventsStorageUnavailableException(operation, unavailable);
        }
    }
}

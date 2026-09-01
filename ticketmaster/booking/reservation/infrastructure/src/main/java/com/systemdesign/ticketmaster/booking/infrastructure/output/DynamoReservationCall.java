package com.systemdesign.ticketmaster.booking.infrastructure.output;

import com.systemdesign.ticketmaster.booking.reservation.infrastructure.ReservationStorageUnavailableException;
import java.util.Objects;
import java.util.function.Supplier;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

final class DynamoReservationCall {
    private DynamoReservationCall() {
    }

    static <T> T execute(String operation, Supplier<T> call) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(call, "call");
        try {
            return call.get();
        } catch (ConditionalCheckFailedException | TransactionCanceledException expectedConditionalOutcome) {
            throw expectedConditionalOutcome;
        } catch (DynamoDbException | SdkClientException unavailable) {
            throw new ReservationStorageUnavailableException(operation, unavailable);
        }
    }

    static void execute(String operation, Runnable call) {
        execute(operation, () -> {
            call.run();
            return null;
        });
    }
}

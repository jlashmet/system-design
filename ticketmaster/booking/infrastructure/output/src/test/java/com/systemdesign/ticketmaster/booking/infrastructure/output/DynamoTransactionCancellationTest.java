package com.systemdesign.ticketmaster.booking.infrastructure.output;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

class DynamoTransactionCancellationTest {
    private TransactionCanceledException exception;
    private boolean nonConditionalFailure;

    @Test
    void conditionalCheckFailureRemainsBusinessConflict() {
        givenCancellationReasons("None", "ConditionalCheckFailed");
        whenCancellationIsClassified();
        thenExpectNonConditionalFailure(false);
    }

    @Test
    void throttlingIsStorageFailureNotSeatConflict() {
        givenCancellationReasons("None", "ThrottlingError");
        whenCancellationIsClassified();
        thenExpectNonConditionalFailure(true);
    }

    @Test
    void transactionConflictIsRetryableStorageFailure() {
        givenCancellationReasons("TransactionConflict", "None");
        whenCancellationIsClassified();
        thenExpectNonConditionalFailure(true);
    }

    @Test
    void missingMeaningfulReasonsFailClosedAsStorageFailure() {
        givenCancellationReasons("None", "None");
        whenCancellationIsClassified();
        thenExpectNonConditionalFailure(true);
    }

    private void givenCancellationReasons(String... codes) {
        List<CancellationReason> reasons = java.util.Arrays.stream(codes)
                .map(code -> CancellationReason.builder().code(code).build())
                .toList();
        exception = TransactionCanceledException.builder()
                .message("synthetic cancellation")
                .cancellationReasons(reasons)
                .build();
        nonConditionalFailure = false;
    }

    private void whenCancellationIsClassified() {
        nonConditionalFailure = DynamoTransactionCancellation.hasNonConditionalFailure(exception);
    }

    private void thenExpectNonConditionalFailure(boolean expected) {
        assertThat(nonConditionalFailure).isEqualTo(expected);
    }
}

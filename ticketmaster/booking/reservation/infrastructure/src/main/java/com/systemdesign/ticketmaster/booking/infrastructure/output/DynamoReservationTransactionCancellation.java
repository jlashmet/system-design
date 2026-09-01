package com.systemdesign.ticketmaster.booking.infrastructure.output;

import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

final class DynamoReservationTransactionCancellation {
    private DynamoReservationTransactionCancellation() {
    }

    static boolean hasNonConditionalFailure(TransactionCanceledException exception) {
        if (exception == null || exception.cancellationReasons() == null) return true;

        boolean sawConditionalFailure = false;
        for (CancellationReason reason : exception.cancellationReasons()) {
            if (reason == null) continue;
            String code = reason.code();
            if (code == null || code.isBlank() || "None".equals(code)) continue;
            if (!"ConditionalCheckFailed".equals(code)) return true;
            sawConditionalFailure = true;
        }
        return !sawConditionalFailure;
    }
}

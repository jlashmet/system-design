package com.systemdesign.ticketmaster.booking.infrastructure.output;

import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

final class DynamoTransactionCancellation {
    private DynamoTransactionCancellation() {
    }

    /**
     * Returns false only when Dynamo positively reports that every meaningful cancellation
     * reason is a conditional-check failure. Missing/unknown reasons are infrastructure
     * failures, because treating an ambiguous cancellation as a seat conflict would tell
     * the caller to choose different seats when the correct action may simply be to retry.
     */
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

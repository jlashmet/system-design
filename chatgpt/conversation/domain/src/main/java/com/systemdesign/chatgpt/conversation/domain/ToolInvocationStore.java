package com.systemdesign.chatgpt.conversation.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public interface ToolInvocationStore {
    Claim claim(
            UUID generationId,
            UUID callId,
            String toolName,
            String requestFingerprint,
            Instant now,
            Instant leaseUntil);

    void complete(
            UUID generationId,
            UUID callId,
            UUID claimToken,
            ToolResult result,
            Instant completedAt);

    enum ClaimStatus {
        CLAIMED,
        COMPLETED,
        BUSY
    }

    record Claim(ClaimStatus status, UUID claimToken, ToolResult completedResult) {
        public Claim {
            Objects.requireNonNull(status, "status");
            if (status == ClaimStatus.CLAIMED && claimToken == null) {
                throw new IllegalArgumentException("claimed invocation requires claimToken");
            }
            if (status == ClaimStatus.COMPLETED && completedResult == null) {
                throw new IllegalArgumentException("completed invocation requires completedResult");
            }
        }

        public static Claim claimed(UUID claimToken) {
            return new Claim(ClaimStatus.CLAIMED, Objects.requireNonNull(claimToken), null);
        }

        public static Claim completed(ToolResult result) {
            return new Claim(ClaimStatus.COMPLETED, null, Objects.requireNonNull(result));
        }

        public static Claim busy() {
            return new Claim(ClaimStatus.BUSY, null, null);
        }
    }
}

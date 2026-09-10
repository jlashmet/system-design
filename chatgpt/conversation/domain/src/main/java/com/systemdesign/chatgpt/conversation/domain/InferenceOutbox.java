package com.systemdesign.chatgpt.conversation.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface InferenceOutbox {
    Optional<Entry> claimNext(Instant claimedAt, Instant leaseUntil);

    boolean markDispatched(UUID generationId, UUID claimToken, Instant dispatchedAt);

    boolean release(UUID generationId, UUID claimToken);

    record Entry(UUID generationId, UUID claimToken, Instant createdAt, Instant leaseUntil) {
        public Entry {
            if (generationId == null) throw new IllegalArgumentException("generationId is required");
            if (claimToken == null) throw new IllegalArgumentException("claimToken is required");
            if (createdAt == null) throw new IllegalArgumentException("createdAt is required");
            if (leaseUntil == null || !leaseUntil.isAfter(claimedAtOrCreated(createdAt))) {
                throw new IllegalArgumentException("leaseUntil must be after createdAt");
            }
        }

        private static Instant claimedAtOrCreated(Instant createdAt) { return createdAt; }
    }
}

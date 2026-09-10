package com.systemdesign.chatgpt.conversation.domain;

import java.time.Instant;

public interface InferenceQuota {
    boolean tryAcquire(String subjectId, String idempotencyKey, Instant now);
}

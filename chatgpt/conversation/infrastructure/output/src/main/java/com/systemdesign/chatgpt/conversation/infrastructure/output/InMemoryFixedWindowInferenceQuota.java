package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.InferenceQuota;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class InMemoryFixedWindowInferenceQuota implements InferenceQuota {
    private final int maxRequests;
    private final Duration windowSize;
    private final Map<String, Window> windows = new HashMap<>();

    public InMemoryFixedWindowInferenceQuota(int maxRequests, Duration windowSize) {
        if (maxRequests < 1) {
            throw new IllegalArgumentException("maxRequests must be >= 1");
        }
        if (windowSize == null || windowSize.isZero() || windowSize.isNegative()) {
            throw new IllegalArgumentException("windowSize must be positive");
        }
        this.maxRequests = maxRequests;
        this.windowSize = windowSize;
    }

    @Override
    public synchronized boolean tryAcquire(String subjectId, String idempotencyKey, Instant now) {
        if (subjectId == null || subjectId.isBlank()) {
            throw new IllegalArgumentException("subjectId must not be blank");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        if (now == null) {
            throw new IllegalArgumentException("now must not be null");
        }

        Window window = windows.get(subjectId);
        if (window == null || !now.isBefore(window.startedAt.plus(windowSize))) {
            window = new Window(now, new HashSet<>());
            windows.put(subjectId, window);
        }

        if (window.keys.contains(idempotencyKey)) {
            return true;
        }
        if (window.keys.size() >= maxRequests) {
            return false;
        }
        window.keys.add(idempotencyKey);
        return true;
    }

    private record Window(Instant startedAt, Set<String> keys) {
    }
}

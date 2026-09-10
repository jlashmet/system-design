package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.ToolInvocationStore;
import com.systemdesign.chatgpt.conversation.domain.ToolResult;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class InMemoryToolInvocationStore implements ToolInvocationStore {
    private final Map<Key, Entry> entries = new HashMap<>();

    @Override
    public synchronized Claim claim(UUID generationId, UUID callId, String toolName, String requestFingerprint,
            Instant now, Instant leaseUntil) {
        Objects.requireNonNull(generationId); Objects.requireNonNull(callId); Objects.requireNonNull(now); Objects.requireNonNull(leaseUntil);
        Key key = new Key(generationId, callId);
        Entry current = entries.get(key);
        if (current != null) {
            validateRequest(current, toolName, requestFingerprint);
            if (current.result != null) return Claim.completed(current.result);
            if (current.leaseUntil.isAfter(now)) return Claim.busy();
        }
        UUID token = UUID.randomUUID();
        entries.put(key, new Entry(toolName, requestFingerprint, token, leaseUntil, null));
        return Claim.claimed(token);
    }

    @Override
    public synchronized void complete(UUID generationId, UUID callId, UUID claimToken, ToolResult result, Instant completedAt) {
        Key key = new Key(generationId, callId);
        Entry current = entries.get(key);
        if (current == null || !current.claimToken.equals(claimToken)) {
            throw new IllegalStateException("stale tool invocation claim");
        }
        entries.put(key, new Entry(current.toolName, current.requestFingerprint, current.claimToken, current.leaseUntil,
                Objects.requireNonNull(result)));
    }

    private void validateRequest(Entry current, String toolName, String requestFingerprint) {
        if (!current.toolName.equals(toolName) || !current.requestFingerprint.equals(requestFingerprint)) {
            throw new IllegalStateException("tool call id reused with different request");
        }
    }

    private record Key(UUID generationId, UUID callId) { }
    private record Entry(String toolName, String requestFingerprint, UUID claimToken, Instant leaseUntil, ToolResult result) { }
}

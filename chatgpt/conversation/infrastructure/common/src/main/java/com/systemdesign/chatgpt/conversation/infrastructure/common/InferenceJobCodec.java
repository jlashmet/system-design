package com.systemdesign.chatgpt.conversation.infrastructure.common;

import com.systemdesign.chatgpt.conversation.domain.InferenceJobQueue;

import java.util.Objects;
import java.util.UUID;

public final class InferenceJobCodec {
    private static final String SEPARATOR = ":";

    private InferenceJobCodec() {
    }

    public static String encode(InferenceJobQueue.Job job) {
        Objects.requireNonNull(job, "job");
        return job.generationId() + SEPARATOR + job.attempt();
    }

    public static InferenceJobQueue.Job decode(String body) {
        String firstLine = Objects.requireNonNull(body, "body").lines().findFirst().orElse("");
        int separator = firstLine.lastIndexOf(SEPARATOR);
        if (separator <= 0 || separator == firstLine.length() - 1) {
            throw new IllegalArgumentException("invalid inference job payload");
        }
        UUID generationId = UUID.fromString(firstLine.substring(0, separator));
        int attempt = Integer.parseInt(firstLine.substring(separator + 1));
        return new InferenceJobQueue.Job(generationId, attempt);
    }
}

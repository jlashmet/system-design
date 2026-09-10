package com.systemdesign.chatgpt.conversation.domain;

import java.util.Objects;
import java.util.Set;

public record ModelProfile(
        String model,
        Set<ModelCapability> capabilities,
        long inputCostMicrosPerMillionTokens,
        long outputCostMicrosPerMillionTokens) {

    public ModelProfile {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        if (inputCostMicrosPerMillionTokens < 0 || outputCostMicrosPerMillionTokens < 0) {
            throw new IllegalArgumentException("model cost must not be negative");
        }
    }

    public long relativeCost() {
        return inputCostMicrosPerMillionTokens + outputCostMicrosPerMillionTokens;
    }

    public boolean supports(Set<ModelCapability> requiredCapabilities) {
        return capabilities.containsAll(requiredCapabilities);
    }
}

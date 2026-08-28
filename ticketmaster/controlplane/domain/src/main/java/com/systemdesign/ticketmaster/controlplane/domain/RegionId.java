package com.systemdesign.ticketmaster.controlplane.domain;

import java.util.Objects;

public record RegionId(String value) {
    public RegionId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) throw new IllegalArgumentException("region must not be blank");
    }
}

package com.systemdesign.ticketmaster.events.application;

public sealed interface EventSearchProjectionAction
        permits EventSearchProjection, DeleteEventSearchProjection {
    String eventId();
}

package com.systemdesign.ticketmaster.events.bootstrap;

import com.systemdesign.ticketmaster.events.application.EventSearchProjectionAction;

@FunctionalInterface
interface EventSearchProjectionPublisher {
    void publish(EventSearchProjectionAction action, String deduplicationId);
}

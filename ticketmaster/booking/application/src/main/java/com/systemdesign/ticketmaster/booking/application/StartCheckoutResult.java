package com.systemdesign.ticketmaster.booking.application;

import com.systemdesign.ticketmaster.booking.domain.Booking;
import com.systemdesign.ticketmaster.booking.domain.PaymentIntent;

public record StartCheckoutResult(Booking booking, PaymentIntent paymentIntent) {}

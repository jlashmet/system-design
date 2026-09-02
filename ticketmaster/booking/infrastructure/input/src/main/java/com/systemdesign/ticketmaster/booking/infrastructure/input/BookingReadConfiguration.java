package com.systemdesign.ticketmaster.booking.infrastructure.input;

import com.systemdesign.ticketmaster.booking.application.GetBookingHandler;
import com.systemdesign.ticketmaster.booking.domain.BookingRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BookingReadConfiguration {
    @Bean
    GetBookingHandler getBookingHandler(BookingRepository bookingRepository) {
        return new GetBookingHandler(bookingRepository);
    }
}

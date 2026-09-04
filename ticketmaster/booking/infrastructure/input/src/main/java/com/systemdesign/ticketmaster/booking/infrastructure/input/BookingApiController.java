package com.systemdesign.ticketmaster.booking.infrastructure.input;

import com.systemdesign.ticketmaster.booking.api.BookingApi;
import com.systemdesign.ticketmaster.booking.api.model.CheckoutResponse;
import com.systemdesign.ticketmaster.booking.api.model.Money;
import com.systemdesign.ticketmaster.booking.api.model.SeatMapSeatResponse;
import com.systemdesign.ticketmaster.booking.api.model.SectionResponse;
import com.systemdesign.ticketmaster.booking.api.model.StartCheckoutRequest;
import com.systemdesign.ticketmaster.booking.application.GetSectionSeatsHandler;
import com.systemdesign.ticketmaster.booking.application.GetSectionSeatsQuery;
import com.systemdesign.ticketmaster.booking.application.GetSectionsHandler;
import com.systemdesign.ticketmaster.booking.application.GetSectionsQuery;
import com.systemdesign.ticketmaster.booking.application.StartCheckoutCommand;
import com.systemdesign.ticketmaster.booking.application.StartCheckoutHandler;
import com.systemdesign.ticketmaster.booking.application.StartCheckoutResult;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.Price;
import com.systemdesign.ticketmaster.booking.domain.SeatId;
import com.systemdesign.ticketmaster.booking.domain.SeatMapSeat;
import com.systemdesign.ticketmaster.booking.domain.SectionId;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class BookingApiController implements BookingApi {
    private static final String SECTION_CACHE_CONTROL = "public, max-age=60, stale-while-revalidate=300";
    private static final String NO_STORE = "no-store";

    private final StartCheckoutHandler startCheckoutHandler;
    private final GetSectionsHandler getSectionsHandler;
    private final GetSectionSeatsHandler getSectionSeatsHandler;

    public BookingApiController(StartCheckoutHandler startCheckoutHandler,
                                GetSectionsHandler getSectionsHandler,
                                GetSectionSeatsHandler getSectionSeatsHandler) {
        this.startCheckoutHandler = startCheckoutHandler;
        this.getSectionsHandler = getSectionsHandler;
        this.getSectionSeatsHandler = getSectionSeatsHandler;
    }

    @Override
    public ResponseEntity<List<SectionResponse>> getSections(String eventId) {
        List<SectionResponse> response = getSectionsHandler.handle(new GetSectionsQuery(new EventId(eventId)))
                .stream().map(BookingApiController::toSectionResponse).toList();
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, SECTION_CACHE_CONTROL).body(response);
    }

    @Override
    public ResponseEntity<List<SeatMapSeatResponse>> getSectionSeats(String eventId, String sectionId) {
        List<SeatMapSeatResponse> response = getSectionSeatsHandler
                .handle(new GetSectionSeatsQuery(new EventId(eventId), new SectionId(sectionId)))
                .stream().map(BookingApiController::toSeatResponse).toList();
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, NO_STORE).body(response);
    }

    @Override
    public ResponseEntity<CheckoutResponse> startCheckout(
            String eventId,
            String idempotencyKey,
            String userId,
            StartCheckoutRequest request,
            String admissionToken) {
        List<SeatId> seatIds = request.getSeatIds().stream().map(SeatId::new).toList();
        StartCheckoutResult result = startCheckoutHandler.handle(
                new StartCheckoutCommand(
                        new EventId(eventId),
                        new UserId(userId),
                        seatIds,
                        idempotencyKey,
                        admissionToken));
        CheckoutResponse response = new CheckoutResponse();
        response.setBookingId(result.booking().id().value());
        response.setStatus(result.booking().status().name());
        response.setPaymentIntentId(result.paymentIntentId());
        response.setCheckoutExpiresAt(result.checkoutExpiresAt().atOffset(ZoneOffset.UTC));
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, NO_STORE).body(response);
    }

    private static SectionResponse toSectionResponse(SectionId sectionId) {
        SectionResponse response = new SectionResponse();
        response.setSectionId(sectionId.value());
        return response;
    }

    private static SeatMapSeatResponse toSeatResponse(SeatMapSeat seat) {
        SeatMapSeatResponse response = new SeatMapSeatResponse();
        response.setSeatId(seat.seatId().value());
        response.setSectionId(seat.sectionId().value());
        response.setRow(seat.row());
        response.setNumber(seat.number());
        response.setPrice(toMoney(seat.price()));
        response.setStatus(SeatMapSeatResponse.StatusEnum.fromValue(seat.status().name()));
        return response;
    }

    private static Money toMoney(Price price) {
        Money money = new Money();
        money.setAmount(price.amount());
        money.setCurrency(price.currency().getCurrencyCode());
        return money;
    }
}

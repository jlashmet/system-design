package com.systemdesign.ticketmaster.booking.infrastructure.input;

import com.systemdesign.ticketmaster.booking.api.BookingApi;
import com.systemdesign.ticketmaster.booking.api.model.CheckoutResponse;
import com.systemdesign.ticketmaster.booking.api.model.CreateHoldRequest;
import com.systemdesign.ticketmaster.booking.api.model.HoldResponse;
import com.systemdesign.ticketmaster.booking.api.model.Money;
import com.systemdesign.ticketmaster.booking.api.model.SeatMapSeatResponse;
import com.systemdesign.ticketmaster.booking.api.model.SectionResponse;
import com.systemdesign.ticketmaster.booking.application.CreateHoldCommand;
import com.systemdesign.ticketmaster.booking.application.CreateHoldHandler;
import com.systemdesign.ticketmaster.booking.application.GetSectionSeatsHandler;
import com.systemdesign.ticketmaster.booking.application.GetSectionSeatsQuery;
import com.systemdesign.ticketmaster.booking.application.GetSectionsHandler;
import com.systemdesign.ticketmaster.booking.application.GetSectionsQuery;
import com.systemdesign.ticketmaster.booking.application.StartCheckoutCommand;
import com.systemdesign.ticketmaster.booking.application.StartCheckoutHandler;
import com.systemdesign.ticketmaster.booking.application.StartCheckoutResult;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.Hold;
import com.systemdesign.ticketmaster.booking.domain.HoldId;
import com.systemdesign.ticketmaster.booking.domain.HoldIdempotencyKey;
import com.systemdesign.ticketmaster.booking.domain.Price;
import com.systemdesign.ticketmaster.booking.domain.SeatId;
import com.systemdesign.ticketmaster.booking.domain.SeatMapSeat;
import com.systemdesign.ticketmaster.booking.domain.SectionId;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class BookingApiController implements BookingApi {
    private static final String SECTION_CACHE_CONTROL = "public, max-age=60, stale-while-revalidate=300";
    private static final String NO_STORE = "no-store";

    private final CreateHoldHandler createHoldHandler;
    private final StartCheckoutHandler startCheckoutHandler;
    private final GetSectionsHandler getSectionsHandler;
    private final GetSectionSeatsHandler getSectionSeatsHandler;

    public BookingApiController(CreateHoldHandler createHoldHandler, StartCheckoutHandler startCheckoutHandler,
                                GetSectionsHandler getSectionsHandler, GetSectionSeatsHandler getSectionSeatsHandler) {
        this.createHoldHandler = createHoldHandler;
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
    public ResponseEntity<HoldResponse> createHold(String eventId, String idempotencyKey, CreateHoldRequest request) {
        List<SeatId> seatIds = request.getSeatIds().stream().map(SeatId::new).toList();
        Hold hold = createHoldHandler.handle(new CreateHoldCommand(
                new UserId(request.getUserId()), new EventId(eventId), seatIds,
                new HoldIdempotencyKey(idempotencyKey)));
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .body(toHoldResponse(hold));
    }

    @Override
    public ResponseEntity<CheckoutResponse> startCheckout(String eventId, String holdId, String idempotencyKey) {
        StartCheckoutResult result = startCheckoutHandler.handle(
                new StartCheckoutCommand(new EventId(eventId), new HoldId(holdId), idempotencyKey));
        CheckoutResponse response = new CheckoutResponse();
        response.setBookingId(result.booking().id().value());
        response.setStatus(result.booking().status().name());
        response.setPaymentIntentId(result.paymentIntent().id());
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, NO_STORE).body(response);
    }

    private static HoldResponse toHoldResponse(Hold hold) {
        HoldResponse response = new HoldResponse();
        response.setHoldId(hold.id().value());
        response.setEventId(hold.eventId().value());
        response.setUserId(hold.userId().value());
        response.setSeatIds(hold.seatIds().stream().map(SeatId::value).sorted().toList());
        response.setTotalPrice(toMoney(hold.totalPrice()));
        response.setStatus(hold.status().name());
        response.setExpiresAt(hold.expiresAt().atOffset(ZoneOffset.UTC));
        return response;
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
        response.setStatus(seat.status().name());
        return response;
    }

    private static Money toMoney(Price price) {
        Money money = new Money();
        money.setAmount(price.amount());
        money.setCurrency(price.currency().getCurrencyCode());
        return money;
    }
}

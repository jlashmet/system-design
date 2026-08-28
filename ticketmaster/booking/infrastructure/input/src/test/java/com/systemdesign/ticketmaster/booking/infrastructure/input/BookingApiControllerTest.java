package com.systemdesign.ticketmaster.booking.infrastructure.input;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.booking.api.model.CreateHoldRequest;
import com.systemdesign.ticketmaster.booking.api.model.HoldResponse;
import com.systemdesign.ticketmaster.booking.api.model.SectionResponse;
import com.systemdesign.ticketmaster.booking.application.CreateHoldHandler;
import com.systemdesign.ticketmaster.booking.application.GetSectionsHandler;
import com.systemdesign.ticketmaster.booking.domain.EventAdmission;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.Hold;
import com.systemdesign.ticketmaster.booking.domain.HoldId;
import com.systemdesign.ticketmaster.booking.domain.HoldIdempotencyKey;
import com.systemdesign.ticketmaster.booking.domain.HoldRepository;
import com.systemdesign.ticketmaster.booking.domain.Price;
import com.systemdesign.ticketmaster.booking.domain.SeatId;
import com.systemdesign.ticketmaster.booking.domain.SeatMapRepository;
import com.systemdesign.ticketmaster.booking.domain.SeatMapSeat;
import com.systemdesign.ticketmaster.booking.domain.SeatPriceQuote;
import com.systemdesign.ticketmaster.booking.domain.SectionId;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomEntry;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

class BookingApiControllerTest {
    private static final Instant NOW = Instant.parse("2026-08-28T18:00:00Z");
    private static final Price PRICE = new Price(new BigDecimal("125.00"), Currency.getInstance("USD"));

    private BookingApiController controller;
    private ResponseEntity<List<SectionResponse>> sectionsResponse;
    private ResponseEntity<HoldResponse> holdResponse;
    private CapturingHoldRepository holdRepository;

    @Test
    void mapsProjectedSectionsToApiContractWithShortSharedCachePolicy() {
        givenProjectedSections();
        whenSectionsAreRequested();
        thenExpectShortSharedCacheResponse();
    }

    @Test
    void createHoldUsesTrustedHeaderIdentityRatherThanRequestBodyIdentity() {
        givenHoldCreationBoundary();
        whenHoldIsCreatedForHeaderUser();
        thenExpectHeaderUserOwnsHold();
    }

    private void givenProjectedSections() {
        SeatMapRepository repository = new FakeSeatMapRepository();
        controller = new BookingApiController(
                null,
                null,
                new GetSectionsHandler(repository),
                null);
        sectionsResponse = null;
        holdResponse = null;
    }

    private void givenHoldCreationBoundary() {
        holdRepository = new CapturingHoldRepository();
        CreateHoldHandler createHoldHandler = new CreateHoldHandler(
                ignored -> {},
                holdRepository,
                new DisabledWaitingRoomRepository(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(5));
        controller = new BookingApiController(createHoldHandler, null, null, null);
        sectionsResponse = null;
        holdResponse = null;
    }

    private void whenSectionsAreRequested() {
        sectionsResponse = controller.getSections("event-123");
    }

    private void whenHoldIsCreatedForHeaderUser() {
        CreateHoldRequest request = new CreateHoldRequest();
        request.setSeatIds(List.of("A10"));
        holdResponse = controller.createHold("event-123", "hold-request-1", "user-header", request);
    }

    private void thenExpectShortSharedCacheResponse() {
        assertThat(sectionsResponse.getStatusCode().value()).isEqualTo(200);
        assertThat(sectionsResponse.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("public, max-age=60, stale-while-revalidate=300");
        assertThat(sectionsResponse.getBody()).extracting(SectionResponse::getSectionId)
                .containsExactly("101", "102");
    }

    private void thenExpectHeaderUserOwnsHold() {
        assertThat(holdResponse.getStatusCode().value()).isEqualTo(201);
        assertThat(holdResponse.getBody()).isNotNull();
        assertThat(holdResponse.getBody().getUserId()).isEqualTo("user-header");
        assertThat(holdRepository.createdHold).isNotNull();
        assertThat(holdRepository.createdHold.userId()).isEqualTo(new UserId("user-header"));
        assertThat(holdRepository.idempotencyKey).isEqualTo(new HoldIdempotencyKey("hold-request-1"));
    }

    private static final class FakeSeatMapRepository implements SeatMapRepository {
        @Override public void upsert(SeatMapSeat seat) {}

        @Override
        public List<SectionId> findSections(EventId eventId) {
            assertThat(eventId).isEqualTo(new EventId("event-123"));
            return List.of(new SectionId("101"), new SectionId("102"));
        }

        @Override
        public List<SeatMapSeat> findSection(EventId eventId, SectionId sectionId) {
            return List.of();
        }
    }

    private static final class CapturingHoldRepository implements HoldRepository {
        private Hold createdHold;
        private HoldIdempotencyKey idempotencyKey;

        @Override
        public SeatPriceQuote quoteSeatPrices(EventId eventId, Set<SeatId> seatIds) {
            return new SeatPriceQuote(eventId, Map.of(new SeatId("A10"), PRICE));
        }

        @Override
        public void createWithSeatClaims(
                Hold hold,
                SeatPriceQuote quote,
                Instant now,
                HoldIdempotencyKey idempotencyKey) {
            this.createdHold = hold;
            this.idempotencyKey = idempotencyKey;
        }

        @Override public Optional<Hold> findById(HoldId holdId) { return Optional.empty(); }
        @Override public Optional<Hold> findByIdempotencyKey(HoldIdempotencyKey key) { return Optional.empty(); }
    }

    private static final class DisabledWaitingRoomRepository implements WaitingRoomRepository {
        @Override public WaitingRoomEntry join(WaitingRoomEntry entry) { return entry; }
        @Override public Optional<WaitingRoomEntry> findEntry(EventId eventId, UserId userId) { return Optional.empty(); }
        @Override public Optional<EventAdmission> findAdmission(EventId eventId) { return Optional.empty(); }
        @Override public EventAdmission advanceAdmission(EventAdmission admission) { return admission; }
    }
}

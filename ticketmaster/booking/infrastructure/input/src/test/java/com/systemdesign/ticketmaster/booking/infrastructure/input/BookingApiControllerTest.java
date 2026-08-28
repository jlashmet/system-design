package com.systemdesign.ticketmaster.booking.infrastructure.input;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.booking.api.model.SectionResponse;
import com.systemdesign.ticketmaster.booking.application.GetSectionsHandler;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.SeatMapRepository;
import com.systemdesign.ticketmaster.booking.domain.SeatMapSeat;
import com.systemdesign.ticketmaster.booking.domain.SectionId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class BookingApiControllerTest {
    @Test
    void mapsProjectedSectionsToApiContract() {
        SeatMapRepository repository = new FakeSeatMapRepository();
        BookingApiController controller = new BookingApiController(
                null,
                null,
                new GetSectionsHandler(repository),
                null);

        ResponseEntity<List<SectionResponse>> response = controller.getSections("event-123");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).extracting(SectionResponse::getSectionId)
                .containsExactly("101", "102");
    }

    private static final class FakeSeatMapRepository implements SeatMapRepository {
        @Override
        public void upsert(SeatMapSeat seat) {}

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
}

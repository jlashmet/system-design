package com.systemdesign.ticketmaster.booking.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.SeatMapRepository;
import com.systemdesign.ticketmaster.booking.domain.SeatMapSeat;
import com.systemdesign.ticketmaster.booking.domain.SectionId;
import java.util.List;
import org.junit.jupiter.api.Test;

class GetSectionsHandlerTest {
    private static final EventId EVENT_ID = new EventId("event-123");

    @Test
    void returnsSectionDirectoryFromSeatMapProjection() {
        SeatMapRepository repository = new FakeSeatMapRepository(
                List.of(new SectionId("101"), new SectionId("102")));
        GetSectionsHandler handler = new GetSectionsHandler(repository);

        List<SectionId> sections = handler.handle(new GetSectionsQuery(EVENT_ID));

        assertThat(sections).containsExactly(new SectionId("101"), new SectionId("102"));
    }

    private static final class FakeSeatMapRepository implements SeatMapRepository {
        private final List<SectionId> sections;

        private FakeSeatMapRepository(List<SectionId> sections) {
            this.sections = List.copyOf(sections);
        }

        @Override
        public void upsert(SeatMapSeat seat) {}

        @Override
        public List<SectionId> findSections(EventId eventId) {
            assertThat(eventId).isEqualTo(EVENT_ID);
            return sections;
        }

        @Override
        public List<SeatMapSeat> findSection(EventId eventId, SectionId sectionId) {
            return List.of();
        }
    }
}

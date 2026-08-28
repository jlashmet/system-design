package com.systemdesign.ticketmaster.booking.infrastructure.output;

import static org.assertj.core.api.Assertions.assertThat;

import com.systemdesign.ticketmaster.booking.domain.AdmissionWatermarkRegressionException;
import com.systemdesign.ticketmaster.booking.domain.EventAdmission;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import com.systemdesign.ticketmaster.booking.domain.UserId;
import com.systemdesign.ticketmaster.booking.domain.WaitingRoomEntry;
import io.floci.testcontainers.FlociContainer;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

@Testcontainers
class DynamoWaitingRoomRepositoryIT {
    private static final EventId EVENT_ID = new EventId("event-123");
    private static final UserId USER_ID = new UserId("user-456");
    private static final Instant FIRST_JOIN = Instant.parse("2026-08-27T22:00:00Z");

    @Container
    static final FlociContainer FLOCI = new FlociContainer();

    private DynamoDbClient dynamoDb;
    private DynamoWaitingRoomRepository repository;
    private String tableName;
    private WaitingRoomEntry firstJoin;
    private WaitingRoomEntry secondJoin;
    private EventAdmission firstAdmission;
    private EventAdmission secondAdmission;
    private boolean beforeWatermark;
    private boolean atWatermark;
    private Throwable thrown;

    @AfterEach
    void tearDown() {
        if (dynamoDb != null) {
            if (tableName != null) dynamoDb.deleteTable(DeleteTableRequest.builder().tableName(tableName).build());
            dynamoDb.close();
        }
    }

    @Test
    void repeatJoinPreservesOriginalTimestamp() {
        givenWaitingRoom();
        whenJoinTwice();
        thenExpectOriginalJoinPreserved();
    }

    @Test
    void admissionIsTimestampWatermarkComparison() {
        givenJoinedUserInEnabledWaitingRoom();
        whenAdvanceWatermarkAcrossUser();
        thenExpectWaitingThenAdmitted();
    }

    @Test
    void admissionWatermarkCannotMoveBackward() {
        givenAdmissionAt(FIRST_JOIN.plusSeconds(10));
        whenAdvanceAdmissionTo(FIRST_JOIN.plusSeconds(9));
        thenExpect(AdmissionWatermarkRegressionException.class);
    }

    @Test
    void admissionInitializationPreservesFirstWatermark() {
        givenWaitingRoom();
        whenInitializeAdmissionTwice();
        thenExpectFirstAdmissionPreserved();
    }

    @Test
    void admissionCannotAdvanceBeforeWaitingRoomIsEnabled() {
        givenWaitingRoom();
        whenAdvanceAdmissionTo(FIRST_JOIN);
        thenExpect(AdmissionWatermarkRegressionException.class);
    }

    private void givenWaitingRoom() {
        initialize();
        firstJoin = null;
        secondJoin = null;
        firstAdmission = null;
        secondAdmission = null;
        thrown = null;
    }

    private void givenJoinedUserInEnabledWaitingRoom() {
        givenWaitingRoom();
        repository.initializeAdmission(new EventAdmission(EVENT_ID, FIRST_JOIN.minusSeconds(10)));
        firstJoin = repository.join(new WaitingRoomEntry(EVENT_ID, USER_ID, FIRST_JOIN));
    }

    private void givenAdmissionAt(Instant admittedThrough) {
        givenWaitingRoom();
        repository.initializeAdmission(new EventAdmission(EVENT_ID, admittedThrough));
    }

    private void whenJoinTwice() {
        firstJoin = repository.join(new WaitingRoomEntry(EVENT_ID, USER_ID, FIRST_JOIN));
        secondJoin = repository.join(new WaitingRoomEntry(EVENT_ID, USER_ID, FIRST_JOIN.plusSeconds(30)));
    }

    private void whenInitializeAdmissionTwice() {
        firstAdmission = repository.initializeAdmission(new EventAdmission(EVENT_ID, FIRST_JOIN));
        secondAdmission = repository.initializeAdmission(new EventAdmission(EVENT_ID, FIRST_JOIN.plusSeconds(30)));
    }

    private void whenAdvanceWatermarkAcrossUser() {
        EventAdmission before = repository.advanceAdmission(new EventAdmission(EVENT_ID, FIRST_JOIN.minusMillis(1)));
        beforeWatermark = before.admits(firstJoin);
        EventAdmission at = repository.advanceAdmission(new EventAdmission(EVENT_ID, FIRST_JOIN));
        atWatermark = at.admits(firstJoin);
    }

    private void whenAdvanceAdmissionTo(Instant admittedThrough) {
        try {
            repository.advanceAdmission(new EventAdmission(EVENT_ID, admittedThrough));
        } catch (Throwable error) {
            thrown = error;
        }
    }

    private void thenExpectOriginalJoinPreserved() {
        assertThat(firstJoin.joinedAt()).isEqualTo(FIRST_JOIN);
        assertThat(secondJoin).isEqualTo(firstJoin);
        assertThat(repository.findEntry(EVENT_ID, USER_ID)).contains(firstJoin);
    }

    private void thenExpectFirstAdmissionPreserved() {
        assertThat(firstAdmission.admittedThrough()).isEqualTo(FIRST_JOIN);
        assertThat(secondAdmission).isEqualTo(firstAdmission);
        assertThat(repository.findAdmission(EVENT_ID)).contains(firstAdmission);
    }

    private void thenExpectWaitingThenAdmitted() {
        assertThat(beforeWatermark).isFalse();
        assertThat(atWatermark).isTrue();
    }

    private void thenExpect(Class<? extends Throwable> type) {
        assertThat(thrown).isInstanceOf(type);
    }

    private void initialize() {
        dynamoDb = DynamoDbClient.builder()
                .endpointOverride(URI.create(FLOCI.getEndpoint()))
                .region(Region.of(FLOCI.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(FLOCI.getAccessKey(), FLOCI.getSecretKey())))
                .build();
        tableName = "ticketmaster-waiting-room-" + UUID.randomUUID();
        dynamoDb.createTable(CreateTableRequest.builder()
                .tableName(tableName)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(AttributeDefinition.builder()
                        .attributeName("pk").attributeType(ScalarAttributeType.S).build())
                .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build())
                .build());
        repository = new DynamoWaitingRoomRepository(dynamoDb, tableName);
    }
}

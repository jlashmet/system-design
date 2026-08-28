package com.systemdesign.ticketmaster.booking.infrastructure.output;

import com.systemdesign.ticketmaster.booking.domain.AdmissionRegulationLeaseGateway;
import com.systemdesign.ticketmaster.booking.domain.EventId;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

public final class DynamoAdmissionRegulationLeaseGateway implements AdmissionRegulationLeaseGateway {
    private static final String PK = "pk";

    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public DynamoAdmissionRegulationLeaseGateway(DynamoDbClient dynamoDb, String tableName) {
        this.dynamoDb = Objects.requireNonNull(dynamoDb, "dynamoDb");
        this.tableName = Objects.requireNonNull(tableName, "tableName");
        if (tableName.isBlank()) throw new IllegalArgumentException("tableName must not be blank");
    }

    @Override
    public boolean tryAcquireOrRenew(
            EventId eventId,
            String regulatorId,
            Instant now,
            Instant leaseExpiresAt) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(regulatorId, "regulatorId");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
        if (regulatorId.isBlank()) throw new IllegalArgumentException("regulatorId must not be blank");
        if (!leaseExpiresAt.isAfter(now)) throw new IllegalArgumentException("lease expiry must be after now");

        try {
            DynamoBookingCall.execute("admission regulator lease", () -> dynamoDb.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of(PK, string(DynamoWaitingRoomRepository.admissionPk(eventId))))
                    .updateExpression("SET #regulatorId = :regulatorId, #leaseExpiresAt = :leaseExpiresAt")
                    .conditionExpression("attribute_exists(#pk) AND (attribute_not_exists(#leaseExpiresAt) "
                            + "OR #leaseExpiresAt <= :now OR #regulatorId = :regulatorId)")
                    .expressionAttributeNames(Map.of(
                            "#pk", PK,
                            "#regulatorId", "regulatorId",
                            "#leaseExpiresAt", "regulatorLeaseExpiresAt"))
                    .expressionAttributeValues(Map.of(
                            ":regulatorId", string(regulatorId),
                            ":now", number(now.toEpochMilli()),
                            ":leaseExpiresAt", number(leaseExpiresAt.toEpochMilli())))
                    .build()));
            return true;
        } catch (ConditionalCheckFailedException notOwner) {
            return false;
        }
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue number(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }
}

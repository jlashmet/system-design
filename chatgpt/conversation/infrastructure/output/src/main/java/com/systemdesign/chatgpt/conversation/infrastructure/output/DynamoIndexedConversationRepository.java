package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.Conversation;
import com.systemdesign.chatgpt.conversation.domain.ConversationRepository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Maintains the subject-scoped conversation-list index when canonical conversation metadata is saved. */
public final class DynamoIndexedConversationRepository implements ConversationRepository {
    private final ConversationRepository delegate;
    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public DynamoIndexedConversationRepository(ConversationRepository delegate, DynamoDbClient dynamoDb, String tableName) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.dynamoDb = Objects.requireNonNull(dynamoDb, "dynamoDb");
        if (tableName == null || tableName.isBlank()) throw new IllegalArgumentException("tableName must not be blank");
        this.tableName = tableName;
    }

    @Override
    public Optional<Conversation> findById(UUID conversationId) {
        return delegate.findById(conversationId);
    }

    @Override
    public void save(Conversation conversation) {
        Objects.requireNonNull(conversation, "conversation");
        Map<String, AttributeValue> meta = Map.of(
                "pk", string("CONV#" + conversation.id()),
                "sk", string("META"),
                "entityType", string("CONVERSATION"),
                "userId", string(conversation.userId()),
                "createdAt", number(conversation.createdAt().toEpochMilli()));
        Map<String, AttributeValue> index = Map.of(
                "pk", string("USER#" + conversation.userId()),
                "sk", string(conversationIndexSk(conversation)),
                "entityType", string("USER_CONVERSATION"),
                "conversationId", string(conversation.id().toString()),
                "userId", string(conversation.userId()),
                "createdAt", number(conversation.createdAt().toEpochMilli()));
        dynamoDb.transactWriteItems(TransactWriteItemsRequest.builder()
                .transactItems(put(meta), put(index))
                .build());
        if (!conversation.messages().isEmpty()) {
            // Current creation path is empty. Preserve the broader repository contract for callers that save snapshots.
            delegate.save(conversation);
        }
    }

    private TransactWriteItem put(Map<String, AttributeValue> item) {
        return TransactWriteItem.builder().put(Put.builder().tableName(tableName).item(item).build()).build();
    }

    static String conversationIndexSk(Conversation conversation) {
        return "CONV#%019d#%s".formatted(conversation.createdAt().toEpochMilli(), conversation.id());
    }

    private static AttributeValue string(String value) { return AttributeValue.builder().s(value).build(); }
    private static AttributeValue number(long value) { return AttributeValue.builder().n(Long.toString(value)).build(); }
}

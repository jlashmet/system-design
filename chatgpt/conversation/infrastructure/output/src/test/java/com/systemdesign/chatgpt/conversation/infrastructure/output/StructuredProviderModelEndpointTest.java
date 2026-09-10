package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.Message;
import com.systemdesign.chatgpt.conversation.domain.MessageRole;
import com.systemdesign.chatgpt.conversation.domain.ModelCapability;
import com.systemdesign.chatgpt.conversation.domain.ModelGateway;
import com.systemdesign.chatgpt.conversation.domain.ModelProfile;
import com.systemdesign.chatgpt.conversation.domain.ToolCall;
import com.systemdesign.chatgpt.conversation.domain.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredProviderModelEndpointTest {
    @Test
    void mapsToolSchemasMessagesAndStableToolCallIds() {
        UUID callId = UUID.randomUUID();
        AtomicReference<StructuredProviderClient.Request> captured = new AtomicReference<>();
        StructuredProviderClient client = (request, delta) -> {
            captured.set(request);
            return new StructuredProviderClient.ToolCallResponse(List.of(
                    new StructuredProviderClient.ProviderToolCall(callId, "lookup", Map.of(
                            "key", new StructuredProviderClient.StringValue("abc"),
                            "limit", new StructuredProviderClient.IntegerValue(2)))));
        };
        ModelProfile profile = new ModelProfile("provider-model",
                Set.of(ModelCapability.TEXT_GENERATION, ModelCapability.STREAMING, ModelCapability.TOOL_CALLING), 1, 2);
        StructuredProviderModelEndpoint endpoint = new StructuredProviderModelEndpoint(profile, client, () -> true);
        Message user = new Message(UUID.randomUUID(), MessageRole.USER, "find it", Instant.parse("2026-09-10T20:00:00Z"));
        ToolDefinition tool = new ToolDefinition("lookup", "Find a record", List.of(
                new ToolDefinition.Parameter("key", ToolDefinition.Type.STRING, true, "key"),
                new ToolDefinition.Parameter("limit", ToolDefinition.Type.INTEGER, false, "limit")));

        ModelGateway.TurnResult result = endpoint.streamTurn(List.of(user), Set.of(ModelCapability.TOOL_CALLING),
                List.of(tool), ignored -> { });

        assertThat(captured.get().model()).isEqualTo("provider-model");
        assertThat(captured.get().messages()).containsExactly(new StructuredProviderClient.ProviderMessage("user", "find it"));
        assertThat(captured.get().tools().getFirst().name()).isEqualTo("lookup");
        ModelGateway.ToolRequests requests = (ModelGateway.ToolRequests) result;
        ToolCall call = requests.calls().getFirst();
        assertThat(call.id()).isEqualTo(callId);
        assertThat(call.name()).isEqualTo("lookup");
        assertThat(((ToolCall.StringValue) call.arguments().get("key")).value()).isEqualTo("abc");
        assertThat(((ToolCall.IntegerValue) call.arguments().get("limit")).value()).isEqualTo(2);
    }

    @Test
    void forwardsTextDeltasAndMapsFinalResponse() {
        StructuredProviderClient client = (request, delta) -> {
            delta.accept("hel"); delta.accept("lo");
            return new StructuredProviderClient.TextResponse("hello");
        };
        ModelProfile profile = new ModelProfile("provider-model",
                Set.of(ModelCapability.TEXT_GENERATION, ModelCapability.STREAMING), 1, 2);
        StructuredProviderModelEndpoint endpoint = new StructuredProviderModelEndpoint(profile, client, () -> true);
        java.util.ArrayList<String> deltas = new java.util.ArrayList<>();

        ModelGateway.TurnResult result = endpoint.streamTurn(
                List.of(new Message(UUID.randomUUID(), MessageRole.USER, "hi", Instant.now())), Set.of(), List.of(), deltas::add);

        assertThat(deltas).containsExactly("hel", "lo");
        assertThat(((ModelGateway.FinalResponse) result).completion().content()).isEqualTo("hello");
        assertThat(endpoint.healthy()).isTrue();
    }
}

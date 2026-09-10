package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.Message;
import com.systemdesign.chatgpt.conversation.domain.ModelCapability;
import com.systemdesign.chatgpt.conversation.domain.ModelEndpoint;
import com.systemdesign.chatgpt.conversation.domain.ModelGateway;
import com.systemdesign.chatgpt.conversation.domain.ModelProfile;
import com.systemdesign.chatgpt.conversation.domain.ToolCall;
import com.systemdesign.chatgpt.conversation.domain.ToolDefinition;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class StructuredProviderModelEndpoint implements ModelEndpoint {
    private final ModelProfile profile;
    private final StructuredProviderClient client;
    private final BooleanSupplier health;

    public StructuredProviderModelEndpoint(ModelProfile profile, StructuredProviderClient client, BooleanSupplier health) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.client = Objects.requireNonNull(client, "client");
        this.health = Objects.requireNonNull(health, "health");
    }

    @Override public ModelProfile profile() { return profile; }
    @Override public boolean healthy() { return health.getAsBoolean(); }

    @Override
    public Completion complete(List<Message> messages) {
        TurnResult result = streamTurn(messages, Set.of(), List.of(), ignored -> { });
        if (result instanceof FinalResponse finalResponse) return finalResponse.completion();
        throw new IllegalStateException("provider requested a tool during text-only completion");
    }

    @Override
    public TurnResult streamTurn(List<Message> messages, Set<ModelCapability> requiredCapabilities,
            List<ToolDefinition> tools, Consumer<String> deltaConsumer) {
        StructuredProviderClient.Request request = new StructuredProviderClient.Request(
                profile.model(),
                messages.stream().map(message -> new StructuredProviderClient.ProviderMessage(
                        message.role().name().toLowerCase(), message.content())).toList(),
                tools.stream().map(this::toProviderTool).toList());
        StructuredProviderClient.Response response = client.stream(request, deltaConsumer);
        if (response instanceof StructuredProviderClient.TextResponse text) {
            return new FinalResponse(new Completion(profile.model(), text.content()));
        }
        StructuredProviderClient.ToolCallResponse toolCalls = (StructuredProviderClient.ToolCallResponse) response;
        return new ToolRequests(profile.model(), toolCalls.calls().stream().map(this::toToolCall).toList());
    }

    private StructuredProviderClient.ProviderTool toProviderTool(ToolDefinition definition) {
        return new StructuredProviderClient.ProviderTool(
                definition.name(), definition.description(),
                definition.parameters().stream().map(parameter -> new StructuredProviderClient.ProviderParameter(
                        parameter.name(), parameter.type().name().toLowerCase(), parameter.required(), parameter.description()))
                        .toList());
    }

    private ToolCall toToolCall(StructuredProviderClient.ProviderToolCall call) {
        Map<String, ToolCall.Value> arguments = call.arguments().entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> toValue(entry.getValue())));
        return new ToolCall(call.id(), call.name(), arguments);
    }

    private ToolCall.Value toValue(StructuredProviderClient.ProviderValue value) {
        if (value instanceof StructuredProviderClient.StringValue string) return new ToolCall.StringValue(string.value());
        if (value instanceof StructuredProviderClient.IntegerValue integer) return new ToolCall.IntegerValue(integer.value());
        if (value instanceof StructuredProviderClient.NumberValue number) return new ToolCall.NumberValue(number.value());
        if (value instanceof StructuredProviderClient.BooleanValue bool) return new ToolCall.BooleanValue(bool.value());
        throw new IllegalArgumentException("unsupported provider value: " + value.getClass().getName());
    }
}

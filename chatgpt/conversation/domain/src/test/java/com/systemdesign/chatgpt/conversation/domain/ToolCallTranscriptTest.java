package com.systemdesign.chatgpt.conversation.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallTranscriptTest {
    @Test
    void roundTripsTypedArgumentsDeterministically() {
        ToolCall call = new ToolCall(UUID.randomUUID(), "lookup:weather", Map.of(
                "city", new ToolCall.StringValue("Moorpark, CA\nUSA"),
                "days", new ToolCall.IntegerValue(2),
                "confidence", new ToolCall.NumberValue(0.75),
                "metric", new ToolCall.BooleanValue(true)));

        String encoded = ToolCallTranscript.format(List.of(call));

        assertThat(ToolCallTranscript.parse(encoded)).containsExactly(call);
        assertThat(ToolCallTranscript.format(List.of(call))).isEqualTo(encoded);
    }

    @Test
    void remainsBackwardCompatibleWithLegacyIdNameRows() {
        UUID id = UUID.randomUUID();

        assertThat(ToolCallTranscript.parse("Tool requests:\n" + id + ":lookup"))
                .containsExactly(new ToolCall(id, "lookup", Map.of()));
    }

    @Test
    void rejectsMalformedTranscriptWithoutPartiallyReplayingIt() {
        UUID id = UUID.randomUUID();

        assertThat(ToolCallTranscript.parse("Tool requests:\n" + id + ":lookup\nnot-a-call"))
                .isEmpty();
    }
}

package com.systemdesign.chatgpt.conversation.domain;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ToolCallTranscript {
    private static final String PREFIX = "Tool requests:\n";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private ToolCallTranscript() { }

    public static String format(List<ToolCall> calls) {
        if (calls == null || calls.isEmpty()) throw new IllegalArgumentException("tool calls must not be empty");
        return PREFIX + calls.stream().map(ToolCallTranscript::formatCall)
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    public static List<ToolCall> parse(String content) {
        if (content == null || !content.startsWith(PREFIX)) return List.of();
        List<ToolCall> calls = new ArrayList<>();
        for (String line : content.substring(PREFIX.length()).split("\\R")) {
            if (line.isBlank()) continue;
            ToolCall call = parseLine(line);
            if (call == null) return List.of();
            calls.add(call);
        }
        return List.copyOf(calls);
    }

    private static String formatCall(ToolCall call) {
        String arguments = call.arguments().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .map(entry -> encode(entry.getKey()) + ":" + encodeValue(entry.getValue()))
                .collect(java.util.stream.Collectors.joining(","));
        return call.id() + "\t" + encode(call.name()) + "\t" + arguments;
    }

    private static ToolCall parseLine(String line) {
        try {
            String[] fields = line.split("\\t", -1);
            if (fields.length == 3) {
                UUID id = UUID.fromString(fields[0]);
                String name = decode(fields[1]);
                return new ToolCall(id, name, parseArguments(fields[2]));
            }
            int separator = line.indexOf(':');
            if (separator <= 0 || separator == line.length() - 1) return null;
            return new ToolCall(UUID.fromString(line.substring(0, separator)), line.substring(separator + 1), Map.of());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static Map<String, ToolCall.Value> parseArguments(String encoded) {
        if (encoded.isEmpty()) return Map.of();
        Map<String, ToolCall.Value> arguments = new LinkedHashMap<>();
        for (String field : encoded.split(",")) {
            int separator = field.indexOf(':');
            if (separator <= 0 || separator == field.length() - 1) throw new IllegalArgumentException("invalid tool argument");
            String name = decode(field.substring(0, separator));
            ToolCall.Value value = decodeValue(field.substring(separator + 1));
            if (arguments.put(name, value) != null) throw new IllegalArgumentException("duplicate tool argument: " + name);
        }
        return Map.copyOf(arguments);
    }

    private static String encodeValue(ToolCall.Value value) {
        if (value instanceof ToolCall.StringValue string) return "s:" + encode(string.value());
        if (value instanceof ToolCall.IntegerValue integer) return "i:" + integer.value();
        if (value instanceof ToolCall.NumberValue number) return "n:" + Double.toString(number.value());
        if (value instanceof ToolCall.BooleanValue bool) return "b:" + bool.value();
        throw new IllegalArgumentException("unsupported tool argument value: " + value.getClass().getName());
    }

    private static ToolCall.Value decodeValue(String encoded) {
        if (encoded.length() < 3 || encoded.charAt(1) != ':') throw new IllegalArgumentException("invalid tool argument value");
        String value = encoded.substring(2);
        return switch (encoded.charAt(0)) {
            case 's' -> new ToolCall.StringValue(decode(value));
            case 'i' -> new ToolCall.IntegerValue(Long.parseLong(value));
            case 'n' -> new ToolCall.NumberValue(Double.parseDouble(value));
            case 'b' -> {
                if (!"true".equals(value) && !"false".equals(value)) throw new IllegalArgumentException("invalid boolean");
                yield new ToolCall.BooleanValue(Boolean.parseBoolean(value));
            }
            default -> throw new IllegalArgumentException("unsupported tool argument type");
        };
    }

    private static String encode(String value) {
        return ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(DECODER.decode(value), StandardCharsets.UTF_8);
    }
}

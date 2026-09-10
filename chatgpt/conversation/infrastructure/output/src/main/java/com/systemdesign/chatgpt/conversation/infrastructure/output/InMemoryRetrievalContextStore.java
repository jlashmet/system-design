package com.systemdesign.chatgpt.conversation.infrastructure.output;

import com.systemdesign.chatgpt.conversation.domain.RetrievalContextStore;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class InMemoryRetrievalContextStore implements RetrievalContextStore {
    private final Map<UUID, Snippet> snippets = new ConcurrentHashMap<>();

    @Override
    public List<Snippet> search(String userId, String query, int limit) {
        Set<String> queryTerms = terms(query);
        return snippets.values().stream()
                .filter(snippet -> snippet.userId().equals(userId))
                .map(snippet -> new ScoredSnippet(snippet, overlap(queryTerms, terms(snippet.content()))))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingInt(ScoredSnippet::score).reversed()
                        .thenComparing(scored -> scored.snippet().updatedAt(), Comparator.reverseOrder()))
                .limit(limit)
                .map(ScoredSnippet::snippet)
                .toList();
    }

    @Override
    public void upsert(Snippet snippet) {
        snippets.put(snippet.id(), snippet);
    }

    private int overlap(Set<String> left, Set<String> right) {
        int matches = 0;
        for (String term : left) {
            if (right.contains(term)) {
                matches++;
            }
        }
        return matches;
    }

    private Set<String> terms(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        return List.of(text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .stream()
                .filter(term -> !term.isBlank())
                .collect(Collectors.toSet());
    }

    private record ScoredSnippet(Snippet snippet, int score) {
    }
}

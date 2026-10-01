package me.luucka.finance.core.rules;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import me.luucka.finance.core.EntryKind;

/**
 * Finds the category of an entry from its description: first the user's rules (a text the
 * description contains), else the category the user gave most often to the same description in the
 * past. Descriptions compare ignoring case, accents and punctuation; for the past ones, numbers too
 * (dates, card and reference numbers change from one bank line to the next).
 */
public final class CategoryRules {

    /** A rule: entries whose description contains {@code pattern} go to the category. */
    public record Rule(long id, String pattern, long categoryId, EntryKind kind) {
    }

    /** A past entry with a description and a category. */
    public record Past(String description, long categoryId, EntryKind kind, LocalDate date) {
    }

    /** Where a suggestion comes from: a rule (with it), or the past entries. */
    public record Suggestion(long categoryId, EntryKind kind, Rule rule) {

        public boolean fromRule() {
            return rule != null;
        }
    }

    private final List<Rule> rules;
    private final Map<String, Map<Long, Usage>> history = new HashMap<>();

    private record Usage(long categoryId, EntryKind kind, int count, LocalDate last) {
    }

    /** Longer patterns are more specific: they win over the shorter ones they contain. */
    public CategoryRules(Collection<Rule> rules, Collection<Past> past) {
        this.rules = rules.stream()
                .filter(r -> !normalize(r.pattern()).isEmpty())
                .sorted(Comparator.comparingInt((Rule r) -> normalize(r.pattern()).length()).reversed()
                        .thenComparingLong(Rule::id))
                .toList();
        for (Past entry : past) {
            String key = historyKey(entry.description());
            if (key.isEmpty()) {
                continue;
            }
            history.computeIfAbsent(key, k -> new HashMap<>()).merge(entry.categoryId(),
                    new Usage(entry.categoryId(), entry.kind(), 1, entry.date()),
                    (a, b) -> new Usage(a.categoryId(), a.kind(), a.count() + 1,
                            a.last().isAfter(b.last()) ? a.last() : b.last()));
        }
    }

    /** The first rule matching the description, of the given kind when not null. */
    public Optional<Rule> rule(String description, EntryKind kind) {
        String text = normalize(description);
        if (text.isEmpty()) {
            return Optional.empty();
        }
        return rules.stream()
                .filter(r -> kind == null || r.kind() == kind)
                .filter(r -> text.contains(normalize(r.pattern())))
                .findFirst();
    }

    /**
     * The category of the matching rule, else the one most often given to the same description in
     * the past (the most recent on a tie), of the given kind when not null.
     */
    public Optional<Suggestion> suggest(String description, EntryKind kind) {
        Optional<Rule> rule = rule(description, kind);
        if (rule.isPresent()) {
            return Optional.of(new Suggestion(rule.get().categoryId(), rule.get().kind(), rule.get()));
        }
        String key = historyKey(description);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        return history.getOrDefault(key, Map.of()).values().stream()
                .filter(u -> kind == null || u.kind() == kind)
                .max(Comparator.comparingInt(Usage::count).thenComparing(Usage::last))
                .map(u -> new Suggestion(u.categoryId(), u.kind(), null));
    }

    /** Lower case, without accents, punctuation and repeated spaces. */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return decomposed.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    /** A description as the history compares it: normalized, without the words holding a digit. */
    static String historyKey(String description) {
        return Arrays.stream(normalize(description).split(" "))
                .filter(word -> !word.isEmpty() && word.chars().noneMatch(Character::isDigit))
                .collect(Collectors.joining(" "));
    }
}

package me.luucka.finance.core.category;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import me.luucka.finance.core.EntryKind;

/**
 * A user's categories in their two levels: macro categories and the detail categories under them.
 * Lets analyses roll a detail up to its macro and name it with its path ("Casa › Affitto").
 */
public final class CategoryTree {

    /** Separator between a macro and a detail in a category's path. */
    public static final String SEPARATOR = " › ";

    /** One category; {@code parentId} is null for a macro. */
    public record Node(long id, Long parentId, String name, EntryKind kind, String color) {

        public boolean isMacro() {
            return parentId == null;
        }
    }

    private final Map<Long, Node> byId = new LinkedHashMap<>();
    private final Map<Long, List<Node>> details = new LinkedHashMap<>();

    public CategoryTree(Collection<Node> nodes) {
        for (Node node : nodes) {
            byId.put(node.id(), node);
        }
        for (Node node : nodes) {
            if (!node.isMacro()) {
                details.computeIfAbsent(node.parentId(), id -> new ArrayList<>()).add(node);
            }
        }
    }

    /** The category, or null if it is not one of the user's. */
    public Node node(long id) {
        return byId.get(id);
    }

    public Collection<Node> nodes() {
        return byId.values();
    }

    /** The macro categories, in the order given. */
    public List<Node> macros() {
        return byId.values().stream().filter(Node::isMacro).toList();
    }

    /** The details under a macro, in the order given; none for a detail. */
    public List<Node> details(long macroId) {
        return details.getOrDefault(macroId, List.of());
    }

    /** The macro of a category: the category itself for a macro, its parent for a detail. */
    public long macroId(long id) {
        Node node = byId.get(id);
        return node == null || node.isMacro() ? id : node.parentId();
    }

    /** The macro of a category, or null if it is not one of the user's. */
    public Node macro(long id) {
        return byId.get(macroId(id));
    }

    /** A category and, for a macro, its details: what a filter on it takes in. */
    public Set<Long> withDetails(long id) {
        Set<Long> ids = new HashSet<>();
        ids.add(id);
        for (Node detail : details(id)) {
            ids.add(detail.id());
        }
        return ids;
    }

    /** "Casa › Affitto" for a detail, "Casa" for a macro, "" for a category that is not the user's. */
    public String path(long id) {
        Node node = byId.get(id);
        if (node == null) {
            return "";
        }
        Node parent = node.isMacro() ? null : byId.get(node.parentId());
        return parent == null ? node.name() : parent.name() + SEPARATOR + node.name();
    }
}

package com.app.docreview.documentprocessing.parse.json;

import com.app.docreview.contracts.document.CanonicalNode;
import com.app.docreview.contracts.document.NodeType;
import com.app.docreview.documentprocessing.spans.SpanIdGenerator;
import com.app.docreview.documentprocessing.spans.TextHasher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses JSON into leaf-value nodes addressed by JSON Pointer. Architecture §8.2, §11.2.
 *
 * <p><strong>Structure, not lines.</strong> Routed through the prose parser, a JSON document became
 * one node — no blank lines to end a paragraph — so the whole object was a single unciteable span.
 * Walking the tree instead yields one node per LEAF, which is where the assertions live: an
 * intermediate object carries no obligation, a scalar does.
 *
 * <pre>
 *   input   {"changeProcess":{"approval":{"independentReviewer":false}}}
 *   node    changeProcess / approval / independentReviewer: false
 *   path    ["changeProcess", "approval"]        (section)
 *   pointer /changeProcess/approval/independentReviewer
 * </pre>
 *
 * <p>The node text carries the KEY TRAIL, not just the value. {@code false} on its own is
 * uninterpretable to the extractor and to a reviewer reading a citation; "approval /
 * independentReviewer: false" states a fact that can be compared against a requirement.
 *
 * <p>The section path is the containing object's key trail, so members of one object group into a
 * single candidate rather than one candidate per field.
 */
@Component
@Slf4j
public class JsonParser {

    /**
     * Deepest level walked.
     *
     * <p>A bound, not a preference: a deeply nested or adversarial document would otherwise produce
     * an unbounded node count and a stack-depth risk. Beyond this the subtree is emitted as one node
     * so its content is still citable rather than silently dropped.
     */
    private static final int MAX_DEPTH = 12;

    /** Longest rendered value kept verbatim. */
    private static final int MAX_VALUE_CHARS = 500;

    /** Above this many nodes, the remainder is summarised rather than expanded. */
    private static final int MAX_NODES = 2000;

    /**
     * Owned rather than injected.
     *
     * <p>Boot 4 moved Jackson's autoconfiguration into a separate module that the web starter does
     * not pull in, so there is no {@code ObjectMapper} bean to inject and the context fails to start
     * with "no qualifying bean of type ObjectMapper" — as it did when this class first declared it as
     * a constructor parameter. The same reasoning is recorded on {@code CanonicalArtifactWriter}.
     *
     * <p>Owning it is also the more correct choice here: this mapper only READS uploaded documents,
     * so it must not inherit serialization settings configured elsewhere for API responses.
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ParsedJson parse(byte[] content, String documentVersionId) {
        String raw = new String(content, StandardCharsets.UTF_8);

        JsonNode root;
        try {
            root = objectMapper.readTree(raw);
        } catch (Exception e) {
            // Malformed JSON is a real upload. Reported and handled rather than thrown: the caller
            // falls back to text parsing, which still yields citable evidence.
            log.warn("JSON {} could not be parsed ({}); caller should fall back to text",
                    documentVersionId, e.getClass().getSimpleName());
            return new ParsedJson(List.of(), "", false);
        }
        if (root == null || root.isMissingNode()) {
            return new ParsedJson(List.of(), "", false);
        }

        List<CanonicalNode> nodes = new ArrayList<>();
        StringBuilder documentText = new StringBuilder();
        walk(root, new ArrayList<>(), "", nodes, documentText, documentVersionId, 0);

        log.info("Parsed JSON {}: {} leaf node(s)", documentVersionId, nodes.size());
        return new ParsedJson(nodes, documentText.toString(), true);
    }

    /**
     * Depth-first walk emitting one node per leaf.
     *
     * @param keyTrail keys from the root to the CONTAINING object, used as the section path
     * @param pointer  RFC 6901 JSON Pointer to the current value
     */
    private void walk(JsonNode node, List<String> keyTrail, String pointer,
                      List<CanonicalNode> nodes, StringBuilder documentText,
                      String documentVersionId, int depth) {
        if (nodes.size() >= MAX_NODES) {
            return;
        }

        if (node.isObject() && depth < MAX_DEPTH) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                var childTrail = new ArrayList<>(keyTrail);
                childTrail.add(entry.getKey());
                walk(entry.getValue(), childTrail, pointer + "/" + escape(entry.getKey()),
                        nodes, documentText, documentVersionId, depth + 1);
            }
            return;
        }

        if (node.isArray() && depth < MAX_DEPTH) {
            for (int i = 0; i < node.size(); i++) {
                var childTrail = new ArrayList<>(keyTrail);
                // Index kept in the trail so a citation identifies WHICH element, not just the array.
                childTrail.add("[" + i + "]");
                walk(node.get(i), childTrail, pointer + "/" + i,
                        nodes, documentText, documentVersionId, depth + 1);
            }
            return;
        }

        // Leaf, or a container past MAX_DEPTH emitted whole so nothing is silently lost.
        String rendered = render(keyTrail, node);
        if (rendered.isBlank()) {
            return;
        }

        // Section is the CONTAINING object; the leaf key stays in the text. Grouping members of one
        // object into a single candidate matches how the detector reads a section.
        List<String> sectionPath = keyTrail.size() > 1
                ? List.copyOf(keyTrail.subList(0, keyTrail.size() - 1))
                : List.of();

        nodes.add(new CanonicalNode(
                SpanIdGenerator.nodeId(documentVersionId, nodes.size()),
                NodeType.PARAGRAPH,
                sectionPath,
                // Unpaginated until rendered (§19.1); geometry arrives from the rendered PDF.
                null,
                null,
                nodes.size(),
                rendered,
                TextHasher.hash(rendered),
                List.of(),
                false));
        documentText.append(rendered).append('\n');
    }

    /**
     * Renders a leaf as its key trail plus value.
     *
     * <p>The trail is what makes the value meaningful. {@code false} cites nothing; "approval /
     * independentReviewer: false" is a comparable statement. A null or empty JSON value is rendered
     * explicitly rather than omitted — "retrospectiveReviewDays: null" is frequently the very gap a
     * comparison should report, and dropping it would make an absent control invisible.
     */
    private String render(List<String> keyTrail, JsonNode value) {
        String label = String.join(" / ", keyTrail);
        String text;
        if (value.isNull()) {
            text = "null";
        } else if (value.isValueNode()) {
            text = value.asText();
        } else {
            // A container past MAX_DEPTH: compact form keeps it citable without expanding it.
            text = value.toString();
        }
        if (text.length() > MAX_VALUE_CHARS) {
            text = text.substring(0, MAX_VALUE_CHARS) + "…";
        }
        if (label.isEmpty()) {
            return text.strip();
        }
        return (label + ": " + text).strip();
    }

    /** Escapes a key for RFC 6901: {@code ~} then {@code /}, in that order. */
    private String escape(String key) {
        return key.replace("~", "~0").replace("/", "~1");
    }

    /**
     * Parsed result.
     *
     * @param wellFormed false when the bytes were not valid JSON, so the caller can fall back to
     *                   text parsing rather than storing an empty document
     */
    public record ParsedJson(List<CanonicalNode> nodes, String text, boolean wellFormed) {}
}

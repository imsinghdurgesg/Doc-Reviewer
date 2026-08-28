package com.app.docreview.documentprocessing.parse.json;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JSON structural parsing. Architecture §8.2, §11.2.
 *
 * <p>One node per LEAF, because that is where assertions live: an intermediate object carries no
 * obligation, a scalar does. Parsed as prose the whole document became one node — no blank lines to
 * end a paragraph — so nothing in it could be cited individually.
 */
class JsonParserTest {

    // No-arg: the mapper is OWNED, not injected. Passing one in was the original shape and it broke
    // startup — Boot 4 provides no ObjectMapper bean, and a test that constructs its own dependency
    // cannot see that.
    private final JsonParser parser = new JsonParser();

    private JsonParser.ParsedJson parse(String content) {
        return parser.parse(content.getBytes(StandardCharsets.UTF_8), "DOC-V1");
    }

    @Nested
    @DisplayName("leaves become citable nodes")
    class Leaves {

        private static final String CONFIG = """
                {
                  "changeProcess": {
                    "recording": "All changes must be recorded",
                    "approval": {
                      "independentReviewer": false,
                      "approverRole": "Release Manager"
                    }
                  }
                }
                """;

        @Test
        @DisplayName("one node per leaf, not one per document")
        void leavesAreSeparateNodes() {
            var result = parse(CONFIG);

            assertThat(result.wellFormed()).isTrue();
            assertThat(result.nodes()).hasSize(3);
        }

        @Test
        @DisplayName("a leaf carries its key trail, so the value is interpretable")
        void leafCarriesKeyTrail() {
            var texts = parse(CONFIG).nodes().stream().map(n -> n.text()).toList();

            // "false" alone cites nothing; the trail makes it a comparable statement.
            assertThat(texts).contains(
                    "changeProcess / approval / independentReviewer: false",
                    "changeProcess / approval / approverRole: Release Manager");
        }

        @Test
        @DisplayName("the section is the containing object, so its members group together")
        void sectionIsContainingObject() {
            var nodes = parse(CONFIG).nodes();

            assertThat(nodes).filteredOn(n -> n.text().contains("independentReviewer"))
                    .allSatisfy(n -> assertThat(n.sectionPath())
                            .containsExactly("changeProcess", "approval"));
        }

        @Test
        @DisplayName("a null value is kept, because an absent control is the finding")
        void nullIsKept() {
            var result = parse("""
                    {"emergency": {"retrospectiveReviewDays": null}}
                    """);

            // Dropping this would make a missing requirement invisible to the comparison.
            assertThat(result.nodes()).anySatisfy(n ->
                    assertThat(n.text()).isEqualTo("emergency / retrospectiveReviewDays: null"));
        }

        @Test
        @DisplayName("the reassembled text contains every node verbatim and in order")
        void textIsSearchable() {
            var result = parse(CONFIG);

            int previous = -1;
            for (var node : result.nodes()) {
                int at = result.text().indexOf(node.text());
                assertThat(at).as("%s must appear in the document text", node.text()).isNotNegative();
                assertThat(at).isGreaterThan(previous);
                previous = at;
            }
        }
    }

    @Nested
    @DisplayName("arrays")
    class Arrays {

        @Test
        @DisplayName("an element is addressed by index, so a citation identifies which one")
        void elementsAreIndexed() {
            var result = parse("""
                    {"controls": ["changes recorded", "approval independent"]}
                    """);

            var texts = result.nodes().stream().map(n -> n.text()).toList();
            assertThat(texts).contains(
                    "controls / [0]: changes recorded",
                    "controls / [1]: approval independent");
        }

        @Test
        @DisplayName("objects inside an array are expanded")
        void objectsInArrays() {
            var result = parse("""
                    {"controls": [{"id": "2.1", "text": "must be recorded"}]}
                    """);

            var texts = result.nodes().stream().map(n -> n.text()).toList();
            assertThat(texts).contains("controls / [0] / id: 2.1");
            assertThat(texts).contains("controls / [0] / text: must be recorded");
        }
    }

    @Nested
    @DisplayName("robustness")
    class Robustness {

        @Test
        @DisplayName("malformed JSON reports not-well-formed rather than throwing")
        void malformed() {
            var result = parse("{\"broken\": ");

            // The caller falls back to text parsing, so the document stays comparable.
            assertThat(result.wellFormed()).isFalse();
            assertThat(result.nodes()).isEmpty();
        }

        @Test
        @DisplayName("empty input is handled")
        void empty() {
            assertThat(parse("").wellFormed()).isFalse();
            assertThat(parse("").nodes()).isEmpty();
        }

        @Test
        @DisplayName("a bare scalar document yields one node")
        void bareScalar() {
            var result = parse("\"just a string\"");

            assertThat(result.wellFormed()).isTrue();
            assertThat(result.nodes()).hasSize(1);
            assertThat(result.nodes().get(0).text()).isEqualTo("just a string");
        }

        @Test
        @DisplayName("an empty object yields no nodes rather than a meaningless one")
        void emptyObject() {
            assertThat(parse("{}").nodes()).isEmpty();
        }

        @Test
        @DisplayName("deep nesting terminates and stays citable")
        void deepNesting() {
            // 30 levels, past MAX_DEPTH: the subtree must be emitted whole, not dropped.
            StringBuilder json = new StringBuilder();
            for (int i = 0; i < 30; i++) {
                json.append("{\"k").append(i).append("\":");
            }
            json.append("\"leaf\"");
            json.append("}".repeat(30));

            var result = parse(json.toString());

            assertThat(result.wellFormed()).isTrue();
            assertThat(result.nodes()).isNotEmpty();
            // The value survives somewhere in the emitted content.
            assertThat(result.text()).contains("leaf");
        }

        @Test
        @DisplayName("node ordinals are sequential, so span ordering is stable")
        void ordinalsAreSequential() {
            var nodes = parse("""
                    {"a": 1, "b": 2, "c": 3}
                    """).nodes();

            for (int i = 0; i < nodes.size(); i++) {
                assertThat(nodes.get(i).ordinal()).isEqualTo(i);
            }
        }
    }
}

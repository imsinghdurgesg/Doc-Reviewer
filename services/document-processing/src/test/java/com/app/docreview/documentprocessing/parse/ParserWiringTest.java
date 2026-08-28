package com.app.docreview.documentprocessing.parse;

import com.app.docreview.documentprocessing.parse.csv.CsvParser;
import com.app.docreview.documentprocessing.parse.docx.DocxParser;
import com.app.docreview.documentprocessing.parse.json.JsonParser;
import com.app.docreview.documentprocessing.parse.pdf.PdfParser;
import com.app.docreview.documentprocessing.parse.text.TextParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every parser must be constructible by Spring. Architecture §8.2.
 *
 * <p><strong>Why this exists.</strong> {@code JsonParser} first declared {@code ObjectMapper} as a
 * constructor parameter. Its unit tests passed — they built the mapper themselves — and the service
 * then failed to START: Boot 4 moved Jackson's autoconfiguration into a module the web starter does
 * not pull in, so there is no {@code ObjectMapper} bean to inject. The defect was invisible to every
 * test that constructs its own dependencies.
 *
 * <p>Deliberately an {@link ApplicationContextRunner} over the parser beans alone, not
 * {@code @SpringBootTest}: the full context needs Mongo and MinIO, so it cannot run in a unit suite
 * — which is precisely why this gap went unnoticed. This asserts the one property that matters here,
 * that each parser resolves with only the beans a plain context provides.
 */
class ParserWiringTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(ParserBeans.class);

    @Test
    @DisplayName("every parser is constructible without extra autoconfiguration")
    void allParsersResolve() {
        context.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(PdfParser.class);
            assertThat(ctx).hasSingleBean(DocxParser.class);
            assertThat(ctx).hasSingleBean(TextParser.class);
            assertThat(ctx).hasSingleBean(CsvParser.class);
            assertThat(ctx).hasSingleBean(JsonParser.class);
        });
    }

    @Test
    @DisplayName("JsonParser needs no ObjectMapper bean")
    void jsonParserOwnsItsMapper() {
        // The exact regression: an injected mapper makes this context fail with "no qualifying bean
        // of type ObjectMapper", the same error the running service reported.
        context.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(com.fasterxml.jackson.databind.ObjectMapper.class);
            assertThat(ctx.getBean(JsonParser.class)).isNotNull();
        });
    }

    /** Component-scans only the parser package, so no storage or job beans are required. */
    @org.springframework.context.annotation.Configuration
    @org.springframework.context.annotation.ComponentScan(
            basePackageClasses = {PdfParser.class, DocxParser.class, TextParser.class,
                    CsvParser.class, JsonParser.class})
    static class ParserBeans {
    }
}

package com.app.docreview.reviewcore.shared;

import com.app.docreview.contracts.job.ComparisonState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;

import java.util.List;
import java.util.Locale;

/**
 * Reading an unrecognised {@link ComparisonState} degrades one row instead of the whole list.
 * Architecture §19.2, §21.
 *
 * <p><strong>The failure this prevents.</strong> {@code Comparison.state} is a strongly-typed enum, so a
 * value in MongoDB that is not an enum constant throws {@code IllegalArgumentException} during
 * deserialisation. That happens inside the query, so it does not degrade the offending row — it aborts
 * the entire result set. ONE bad document made the history list, the recent-comparisons list and the
 * used-standards endpoint return 500 for every user, including for the 47 comparisons that were fine.
 *
 * <p>That is exactly what happened: a record written with the state {@code COMPARING_REQUIREMENTS},
 * which is not in the enum, broke History until the row was corrected.
 *
 * <p><strong>Why FAILED rather than null.</strong> A null state would flow into {@code terminal()} and
 * report an unreadable comparison as still running, so the recovery sweep would keep re-dispatching it
 * forever. FAILED is terminal and visibly wrong, which is the honest reading of a record whose state
 * cannot be interpreted — it stops the sweep and shows a reviewer something is amiss rather than
 * hiding it behind a plausible in-progress spinner.
 *
 * <p>This does not make bad writes acceptable. It bounds their blast radius to the row that caused
 * them, and logs the value so the cause is findable.
 */
@Configuration
@Slf4j
public class MongoEnumResilienceConfig {

    @Bean
    public MongoCustomConversions mongoCustomConversions() {
        return new MongoCustomConversions(List.of(new ComparisonStateReadConverter()));
    }

    /**
     * Maps a stored string to {@link ComparisonState}, tolerating values the enum does not contain.
     *
     * <p>Registered only for READING. Writes stay strict: an unknown value must never be persisted, and
     * a lenient writer would let a typo become permanent data.
     */
    @ReadingConverter
    static class ComparisonStateReadConverter implements Converter<String, ComparisonState> {

        @Override
        public ComparisonState convert(String source) {
            if (source == null || source.isBlank()) {
                // A missing state is as uninterpretable as an unknown one, and for the same reason
                // must not read as in-progress.
                return ComparisonState.FAILED;
            }
            try {
                return ComparisonState.valueOf(source.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                // The VALUE is logged: it is an internal state name, not document content (§35), and
                // without it there is no way to find which writer produced it.
                log.warn("Comparison has unrecognised state \"{}\"; reading it as FAILED. "
                        + "A comparison state outside ComparisonState indicates a bad write.", source);
                return ComparisonState.FAILED;
            }
        }
    }
}

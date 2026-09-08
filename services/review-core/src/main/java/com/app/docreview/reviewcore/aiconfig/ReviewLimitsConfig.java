package com.app.docreview.reviewcore.aiconfig;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Review limits, set from Admin. Architecture §10.5, §15.5.
 *
 * <p><strong>Why these are stored rather than environment-only.</strong> Both were compile-time
 * constants or environment variables, so tuning either meant a redeploy. The requirement cap in
 * particular is the kind of number an administrator discovers is wrong at the moment a real review is
 * rejected — which is the worst time to need a release.
 *
 * <p><strong>Why one row read by BOTH services.</strong> The requirement cap is enforced twice:
 * review-core rejects an oversized comparison up front, and the worker bounds what it loads as a
 * backstop. Those two numbers must agree. When they were separate environment variables they could
 * drift, and the failure was silent in the worst direction — a comparison passed the front door,
 * then had requirements quietly dropped by the second check and reported as never attempted. A single
 * stored value that the worker fetches per job removes that class of bug rather than documenting it.
 *
 * <p>No {@code @Data} (§25): a generated {@code toString()} is how configuration ends up in logs.
 */
@Document(collection = "review_limits_config")
@Getter
public class ReviewLimitsConfig {

    /** Fixed id: the collection holds exactly one row, and a constant makes that structural. */
    public static final String SINGLETON_ID = "review-limits-config";

    /**
     * The id, INITIALISED to the singleton constant.
     *
     * <p>Declaring the constant without this field was a real defect: Mongo generated a fresh id on
     * every save, so each update inserted a NEW row instead of replacing the one row. The subsequent
     * {@code findById(SINGLETON_ID)} then matched nothing and returned fresh defaults — so a saved
     * value of 8 read back as 1 immediately, and the collection accumulated a row per save.
     *
     * <p>The initialiser is what makes "exactly one row" true rather than merely intended.
     */
    @Id
    private String id = SINGLETON_ID;

    /**
     * Standards proposed when the reviewer selects none.
     *
     * <p><strong>Default 1, and raising it has a measured cost.</strong> Selecting three standards
     * derived from a common framework produced 118 findings where one standard produced 65 — the same
     * control appearing three times, with nothing to tell a reviewer whether that is three problems or
     * one. So the default recommends the single best match and leaves the rest one click away.
     *
     * <p>Configurable because an organization whose standards genuinely do not overlap may want two or
     * three, and that judgement belongs to whoever curates the library rather than to a constant.
     */
    @Setter private int recommendedStandards = 1;

    /**
     * Material requirements a single comparison may span.
     *
     * <p>A safety bound against unbounded fan-out: each requirement is a model call, so this is the
     * ceiling on what one review can cost. Exceeding it is REJECTED up front with the count, never
     * silently truncated — a review that quietly dropped a third of its requirements while reporting
     * "completed" is worse than one that refuses.
     */
    @Setter private int maxRequirementsPerComparison = 500;

    /**
     * Upper bound on the configurable cap.
     *
     * <p>Not a preference. Beyond a few thousand, one review's model calls stop being a bounded cost,
     * and an administrator raising the number to "unlimited" would discover that as a bill rather than
     * as an error. The bound is generous enough that no realistic standard hits it.
     */
    public static final int MAX_ALLOWED_REQUIREMENTS = 2000;

    // NO FIXED CEILING on recommendations.
    //
    // Any constant here was arbitrary: 5 forbade pre-selecting standards the screen was already
    // ranking, and 8 only moved the arbitrary line. The meaningful bound is the number of standards
    // actually available to recommend — recommending more than exist is not a preference an
    // administrator can hold, it is a number with no effect.
    //
    // So the ceiling is computed from the library at read time; see ReviewLimitsConfigService.

}

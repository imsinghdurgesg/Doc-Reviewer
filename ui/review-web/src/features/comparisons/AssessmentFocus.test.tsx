import { render, screen } from "@testing-library/react";
import { AssessmentFocus } from "@/features/comparisons/AssessmentFocus";

/**
 * The assessment focus is shown when present and absent when not. Architecture §19.2.
 *
 * <p>Two properties carry the weight here. It must RENDER the reviewer's text, because guidance
 * changes which findings exist and a reader who cannot see it cannot interpret the review. And it must
 * render NOTHING when there is no focus, because a permanent "no focus given" row on every review that
 * did not use the feature is noise that trains people to ignore the whole block.
 */
describe("AssessmentFocus", () => {
  it("renders the reviewer's focus verbatim", () => {
    render(<AssessmentFocus guidance="emergency change controls only" />);

    expect(screen.getByText("emergency change controls only")).toBeInTheDocument();
    expect(screen.getByText("Assessment focus")).toBeInTheDocument();
  });

  it("renders nothing when no focus was given", () => {
    // Null, undefined and blank all mean "no focus". Whitespace-only in particular can arrive from a
    // form the reviewer typed into and cleared, and it must not produce an empty labelled block.
    const { container: nullCase } = render(<AssessmentFocus guidance={null} />);
    expect(nullCase).toBeEmptyDOMElement();

    const { container: undefinedCase } = render(<AssessmentFocus guidance={undefined} />);
    expect(undefinedCase).toBeEmptyDOMElement();

    const { container: blankCase } = render(<AssessmentFocus guidance="   " />);
    expect(blankCase).toBeEmptyDOMElement();
  });

  it("states that the findings reflect the focus", () => {
    // The caption is the point of the component, not decoration: it tells the reader that a narrowed
    // search is why the finding count looks the way it does.
    render(<AssessmentFocus guidance="retention periods" />);

    expect(screen.getByText(/Findings reflect this focus/i)).toBeInTheDocument();
  });

  it("preserves line breaks in multi-line guidance", () => {
    // Reviewers write several scoping notes on separate lines. Collapsing them would run distinct
    // constraints together into one sentence and change what the focus appears to say.
    render(<AssessmentFocus guidance={"covers India office\nexcludes SaaS"} />);

    const el = screen.getByText(/covers India office/);
    expect(el).toHaveStyle({ whiteSpace: "pre-wrap" });
  });

  it("trims surrounding whitespace before displaying", () => {
    render(<AssessmentFocus guidance="  approval controls  " />);

    expect(screen.getByText("approval controls")).toBeInTheDocument();
  });
});

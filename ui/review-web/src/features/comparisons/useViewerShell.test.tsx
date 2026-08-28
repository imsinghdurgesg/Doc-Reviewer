import { renderHook, act } from "@testing-library/react";
import { useScrollToFinding, useViewerShell } from "@/features/comparisons/useViewerShell";

/**
 * Switching view mode must not leave the shell driving unmounted panes.
 *
 * The bug this pins: React does not null a ref when the element it points at unmounts on a
 * conditional branch. After switching from the text panes to the PDF panes, `leftRef.current` still
 * holds a DETACHED node. Writing `scrollTop` to a detached node silently succeeds and changes
 * nothing, so synchronized scrolling appears broken with no error anywhere — which is exactly how it
 * was reported.
 */

function makePane(scrollHeight: number, clientHeight: number): HTMLDivElement {
  const el = document.createElement("div");
  Object.defineProperty(el, "scrollHeight", { value: scrollHeight, configurable: true });
  Object.defineProperty(el, "clientHeight", { value: clientHeight, configurable: true });
  Object.defineProperty(el, "scrollWidth", { value: 800, configurable: true });
  Object.defineProperty(el, "clientWidth", { value: 800, configurable: true });
  el.scrollTop = 0;
  return el;
}

describe("useViewerShell scroll synchronization", () => {
  it("scrolls the panes independently by default", () => {
    // Documents of very different lengths do not correspond position for position: a two-page
    // procedure against a forty-page standard moves the standard pane in unhelpful jumps. Selecting a
    // finding still drives both panes to their own evidence, which is the navigation that matters.
    const { result } = renderHook(() => useViewerShell());

    const left = makePane(2000, 500);
    const right = makePane(4000, 500);
    document.body.append(left, right);
    result.current.leftRef.current = left;
    result.current.rightRef.current = right;

    left.scrollTop = 750;
    act(() => result.current.onLeftScroll());

    expect(result.current.scrollTogether).toBe(false);
    expect(right.scrollTop).toBe(0);

    left.remove();
    right.remove();
  });

  it("mirrors scroll proportionally once sync is turned on", () => {
    const { result } = renderHook(() => useViewerShell());
    act(() => result.current.setScrollTogether(true));

    const left = makePane(2000, 500);
    const right = makePane(4000, 500);
    document.body.append(left, right);

    result.current.leftRef.current = left;
    result.current.rightRef.current = right;

    // Half way down the left pane's scrollable range (1500).
    left.scrollTop = 750;
    act(() => result.current.onLeftScroll());

    // Half way down the right pane's own range (3500), not the same pixel offset.
    expect(right.scrollTop).toBeCloseTo(1750, 0);

    left.remove();
    right.remove();
  });

  it("does not write to a pane that has been unmounted", () => {
    const { result } = renderHook(() => useViewerShell());
    act(() => result.current.setScrollTogether(true));

    const left = makePane(2000, 500);
    const right = makePane(4000, 500);
    document.body.append(left, right);
    result.current.leftRef.current = left;
    result.current.rightRef.current = right;

    // The text pane unmounts when the reader switches to Original. React leaves the ref pointing at
    // the now-detached node.
    right.remove();
    expect(result.current.rightRef.current).toBe(right);
    expect(right.isConnected).toBe(false);

    left.scrollTop = 750;
    act(() => result.current.onLeftScroll());

    // Untouched. Before the isConnected guard this wrote 1750 to a node nobody could see, and the
    // reader saw scrolling "stop working" with no error.
    expect(right.scrollTop).toBe(0);

    left.remove();
  });

  it("does not read layout from an unmounted source pane", () => {
    const { result } = renderHook(() => useViewerShell());
    act(() => result.current.setScrollTogether(true));

    const left = makePane(2000, 500);
    const right = makePane(4000, 500);
    document.body.append(right);
    result.current.leftRef.current = left; // never attached
    result.current.rightRef.current = right;

    left.scrollTop = 750;
    act(() => result.current.onLeftScroll());

    expect(right.scrollTop).toBe(0);
    right.remove();
  });

  it("does not mirror while an auto-scroll is in progress", () => {
    const { result } = renderHook(() => useViewerShell());
    act(() => result.current.setScrollTogether(true));

    const left = makePane(2000, 500);
    const right = makePane(4000, 500);
    document.body.append(left, right);
    result.current.leftRef.current = left;
    result.current.rightRef.current = right;

    // What useScrollToFinding raises while it sends each pane to its own evidence.
    result.current.suppressSync.current.active = true;

    left.scrollTop = 750;
    act(() => result.current.onLeftScroll());

    // The right pane keeps whatever position its own auto-scroll gave it. Mirroring here is the
    // bug: the cited sentence is at a different relative position in each document.
    expect(right.scrollTop).toBe(0);

    left.remove();
    right.remove();
  });

  it("stops mirroring when scroll-together is off", () => {
    const { result } = renderHook(() => useViewerShell());

    const left = makePane(2000, 500);
    const right = makePane(4000, 500);
    document.body.append(left, right);
    result.current.leftRef.current = left;
    result.current.rightRef.current = right;

    act(() => result.current.setScrollTogether(true));
    act(() => result.current.setScrollTogether(false));

    left.scrollTop = 750;
    act(() => result.current.onLeftScroll());

    expect(right.scrollTop).toBe(0);

    left.remove();
    right.remove();
  });
});

/**
 * Both panes reach their OWN evidence.
 *
 * The reported failure: selecting a finding scrolled one pane to its highlight and left the other
 * showing an unrelated part of the document, so the reader had to hunt for it. Cause was synchronized
 * scrolling overwriting the second pane's target with a proportional mirror of the first — the two
 * features fighting, not the scroll arithmetic being wrong.
 */
describe("useScrollToFinding", () => {
  function paneWithHighlight(
    findingId: string | null,
    { paneTop, highlightTop }: { paneTop: number; highlightTop: number },
  ): { pane: HTMLDivElement; scrollTo: jest.Mock } {
    const pane = document.createElement("div");
    Object.defineProperty(pane, "clientHeight", { value: 500, configurable: true });
    pane.getBoundingClientRect = () => ({ top: paneTop, height: 500 }) as DOMRect;
    pane.scrollTop = 0;

    if (findingId) {
      const mark = document.createElement("mark");
      mark.setAttribute("data-finding-id", findingId);
      mark.getBoundingClientRect = () => ({ top: highlightTop, height: 20 }) as DOMRect;
      pane.append(mark);
    }

    const scrollTo = jest.fn();
    // jsdom does not implement scrollTo; the assertion is on the target it is asked for.
    (pane as unknown as { scrollTo: unknown }).scrollTo = scrollTo;
    document.body.append(pane);
    return { pane, scrollTo };
  }

  it("scrolls each pane to its own highlight, at different offsets", async () => {
    // The same finding sits near the top of the process and far down the standard.
    const left = paneWithHighlight("F-1", { paneTop: 0, highlightTop: 100 });
    const right = paneWithHighlight("F-1", { paneTop: 0, highlightTop: 2400 });

    const suppression = { current: { active: false } };
    const leftRef = { current: left.pane };
    const rightRef = { current: right.pane };

    renderHook(() => useScrollToFinding("F-1", [leftRef, rightRef], suppression));

    await act(async () => {
      await new Promise((resolve) => requestAnimationFrame(resolve));
    });

    expect(left.scrollTo).toHaveBeenCalledTimes(1);
    expect(right.scrollTo).toHaveBeenCalledTimes(1);

    const leftTarget = left.scrollTo.mock.calls[0]?.[0]?.top;
    const rightTarget = right.scrollTo.mock.calls[0]?.[0]?.top;

    // Centred on each pane's own highlight: 100 - 250 + 10 and 2400 - 250 + 10.
    expect(leftTarget).toBeCloseTo(-140, 0);
    expect(rightTarget).toBeCloseTo(2160, 0);
    // The point of the fix: the two targets are independent, not a shared proportion.
    expect(leftTarget).not.toBeCloseTo(rightTarget, 0);

    left.pane.remove();
    right.pane.remove();
  });

  it("suppresses sync while scrolling and releases it afterwards", async () => {
    jest.useFakeTimers();
    const left = paneWithHighlight("F-1", { paneTop: 0, highlightTop: 100 });
    const right = paneWithHighlight("F-1", { paneTop: 0, highlightTop: 2400 });

    const suppression = { current: { active: false } };
    renderHook(() =>
      useScrollToFinding("F-1", [{ current: left.pane }, { current: right.pane }], suppression),
    );

    act(() => {
      jest.advanceTimersByTime(20); // let the rAF callback run
    });
    expect(suppression.current.active).toBe(true);

    act(() => {
      jest.advanceTimersByTime(1000);
    });
    // Released, or the reader's own scrolling would stay unsynced for the rest of the session.
    expect(suppression.current.active).toBe(false);

    jest.useRealTimers();
    left.pane.remove();
    right.pane.remove();
  });

  it("leaves a pane alone when the finding resolves to no highlight there", async () => {
    // A MISSING finding has evidence in the standard but none in the process (§17.3).
    const left = paneWithHighlight(null, { paneTop: 0, highlightTop: 0 });
    const right = paneWithHighlight("F-1", { paneTop: 0, highlightTop: 2400 });

    const suppression = { current: { active: false } };
    renderHook(() =>
      useScrollToFinding("F-1", [{ current: left.pane }, { current: right.pane }], suppression),
    );

    await act(async () => {
      await new Promise((resolve) => requestAnimationFrame(resolve));
    });

    // Staying put says "nothing here"; jumping somewhere would imply evidence that does not exist.
    expect(left.scrollTo).not.toHaveBeenCalled();
    expect(right.scrollTo).toHaveBeenCalledTimes(1);

    left.pane.remove();
    right.pane.remove();
  });
});

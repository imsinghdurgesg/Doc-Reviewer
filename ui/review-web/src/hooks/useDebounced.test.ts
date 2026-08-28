import { act, renderHook } from "@testing-library/react";
import { useDebounced } from "@/hooks/useDebounced";

/**
 * Search debouncing.
 *
 * <p>What this prevents: typing "expense" issued seven requests, six obsolete before they returned.
 * On a slow connection they can also land out of order, so the list briefly shows matches for
 * "expen" after the ones for "expense" — a result that looks like a filtering bug.
 */

beforeEach(() => jest.useFakeTimers());
afterEach(() => jest.useRealTimers());

describe("useDebounced", () => {
  it("returns the initial value immediately", () => {
    // No delay on first render: an empty search box must not wait 300ms to show an unfiltered list.
    const { result } = renderHook(() => useDebounced("expense"));

    expect(result.current).toBe("expense");
  });

  it("does not settle before the delay elapses", () => {
    const { result, rerender } = renderHook(({ value }) => useDebounced(value), {
      initialProps: { value: "e" },
    });

    rerender({ value: "ex" });
    act(() => {
      jest.advanceTimersByTime(299);
    });

    expect(result.current).toBe("e");
  });

  it("settles on the latest value after the delay", () => {
    const { result, rerender } = renderHook(({ value }) => useDebounced(value), {
      initialProps: { value: "e" },
    });

    rerender({ value: "expense" });
    act(() => {
      jest.advanceTimersByTime(300);
    });

    expect(result.current).toBe("expense");
  });

  it("restarts the timer on each change, so only the final value settles", () => {
    // The behaviour that matters: seven keystroke in quick succession produce ONE settled value,
    // not seven.
    const { result, rerender } = renderHook(({ value }) => useDebounced(value), {
      initialProps: { value: "" },
    });

    for (const value of ["e", "ex", "exp", "expe", "expen", "expens", "expense"]) {
      rerender({ value });
      act(() => {
        jest.advanceTimersByTime(100);
      });
    }
    // 700ms of typing at 100ms intervals: never a 300ms gap, so nothing has settled yet.
    expect(result.current).toBe("");

    act(() => {
      jest.advanceTimersByTime(300);
    });
    expect(result.current).toBe("expense");
  });

  it("honours a custom delay", () => {
    const { result, rerender } = renderHook(
      ({ value }) => useDebounced(value, 1000),
      { initialProps: { value: "a" } },
    );

    rerender({ value: "b" });
    act(() => {
      jest.advanceTimersByTime(300);
    });
    expect(result.current).toBe("a");

    act(() => {
      jest.advanceTimersByTime(700);
    });
    expect(result.current).toBe("b");
  });
});

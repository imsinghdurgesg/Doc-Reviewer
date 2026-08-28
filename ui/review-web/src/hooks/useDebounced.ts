import { useEffect, useState } from "react";

/**
 * A value that settles after the user stops changing it.
 *
 * <p>Used for search boxes, where the raw input drives the field and the debounced value drives the
 * query. Typing "expense" otherwise issues seven requests, six of which are obsolete before they
 * return — and on a slow connection the results can arrive out of order, so the list briefly shows
 * matches for "expen" after the ones for "expense".
 *
 * <p>The timer resets on every change and is cleared on unmount, so a request is never issued for a
 * screen the reader has already left.
 *
 * @param delay milliseconds of quiet before the value settles. 300 is short enough to feel
 *              immediate and long enough to cover normal typing cadence.
 */
export function useDebounced<T>(value: T, delay = 300): T {
  const [settled, setSettled] = useState(value);

  useEffect(() => {
    const timer = setTimeout(() => setSettled(value), delay);
    return () => clearTimeout(timer);
  }, [value, delay]);

  return settled;
}

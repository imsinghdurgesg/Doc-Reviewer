import { useCallback, useEffect, useRef, useState } from "react";

/**
 * Two-pane viewer interaction: synchronized scroll, zoom, fit-width, fullscreen.
 *
 * Ported from the reference implementation's review workspace, re-typed and made renderer-agnostic.
 * None of it knows whether a pane is HTML or a PDF canvas — it operates on scroll containers — which
 * is why the same shell serves both panes and both modes.
 *
 * Synchronised scrolling is OPT-IN, not the default — see the state declaration below.
 *
 * The details that make it feel stable, each of which is easy to get subtly wrong:
 *
 * - **Scroll sync is proportional, not absolute.** Two documents of different lengths cannot share a
 *   pixel offset; matching the fraction scrolled keeps them at the same relative position.
 * - **A re-entrancy guard.** Setting `scrollTop` on the target fires its own scroll event, which
 *   would sync back to the source and oscillate. The guard is cleared on the next frame rather than
 *   synchronously, because the event arrives after the current task.
 * - **Zoom preserves the reading position.** Without capturing an anchor first, changing zoom keeps
 *   `scrollTop` and the reader lands somewhere else in the document.
 */

export type ViewMode = "fit-width" | "custom";

const MIN_ZOOM = 50;
const MAX_ZOOM = 200;
const ZOOM_STEP = 10;

/** Content width the fit calculation targets, matching {@code CanonicalHtmlViewer}. */
const CONTENT_WIDTH = 720;

interface ScrollAnchor {
  x: number;
  y: number;
}

function captureAnchor(element: HTMLElement | null): ScrollAnchor | null {
  if (!element) {
    return null;
  }
  // The CENTRE of the viewport, not its top: restoring the top edge drifts when the content grows,
  // whereas the centre is what the reader is actually looking at.
  return {
    x: (element.scrollLeft + element.clientWidth / 2) / Math.max(element.scrollWidth, 1),
    y: (element.scrollTop + element.clientHeight / 2) / Math.max(element.scrollHeight, 1),
  };
}

/**
 * Raised while each pane is being scrolled to its own evidence.
 *
 * Auto-scroll and synchronized scroll are mutually exclusive. Each pane must land on ITS OWN
 * evidence, which sits at a different relative position in each document — the cited sentence might
 * be 10% into the process and 70% into the standard. Left unguarded, `scrollTo` on one pane fires a
 * scroll event, `synchronize` mirrors that position onto the other, and the target the other pane
 * was just sent to is overwritten. Whichever pane moves second wins, and the reader has to hunt for
 * the highlight in the one that lost.
 *
 * Smooth scrolling makes it worse: events fire continuously through the animation, so the two panes
 * fight for its whole duration rather than once.
 */
export interface ScrollSuppression {
  active: boolean;
}

export function useViewerShell(options: { initialZoom?: number } = {}) {
  const [zoom, setZoom] = useState(options.initialZoom ?? 100);
  const [viewMode, setViewMode] = useState<ViewMode>("fit-width");
  /**
   * Independent scrolling by default.
   *
   * <p>Synchronised scrolling assumes the two documents correspond position for position, and they
   * do not: a two-page procedure is measured against a forty-page standard, so mirroring the
   * fraction scrolled moves the standard pane in unhelpful jumps. Selecting a finding still drives
   * BOTH panes to their own evidence, which is the navigation that actually matters here — the toggle
   * remains for documents that genuinely track each other.
   */
  const [scrollTogether, setScrollTogether] = useState(false);
  const [fullscreen, setFullscreen] = useState(false);

  const leftRef = useRef<HTMLDivElement | null>(null);
  const rightRef = useRef<HTMLDivElement | null>(null);
  const documentsAreaRef = useRef<HTMLDivElement | null>(null);

  const syncing = useRef(false);
  const zoomAnchors = useRef<[ScrollAnchor | null, ScrollAnchor | null] | null>(null);
  /** Shared with {@link useScrollToFinding}, which raises it while it drives both panes. */
  const suppressSync = useRef<ScrollSuppression>({ active: false });

  const applyZoom = useCallback((next: number, mode: ViewMode = "custom") => {
    const bounded = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, Math.round(next / 5) * 5));
    zoomAnchors.current = [captureAnchor(leftRef.current), captureAnchor(rightRef.current)];
    setViewMode(mode);
    setZoom(bounded);
  }, []);

  const zoomIn = useCallback(() => applyZoom(zoom + ZOOM_STEP), [applyZoom, zoom]);
  const zoomOut = useCallback(() => applyZoom(zoom - ZOOM_STEP), [applyZoom, zoom]);

  const fitWidth = useCallback(() => {
    const widths = [leftRef.current, rightRef.current]
      .filter((el): el is HTMLDivElement => Boolean(el) && (el as HTMLDivElement).clientWidth > 0)
      .map((el) => el.clientWidth);
    if (widths.length === 0) {
      return;
    }
    // The NARROWER pane governs. Fitting the wider one would overflow the other, and a pane that
    // scrolls horizontally is the thing fit-width exists to prevent.
    const available = Math.min(...widths) - 64;
    applyZoom(Math.floor(((available / CONTENT_WIDTH) * 100) / 5) * 5, "fit-width");
  }, [applyZoom]);

  /** Mirrors a pane's scroll position onto the other, proportionally. */
  const synchronize = useCallback(
    (source: HTMLElement | null, target: HTMLElement | null) => {
      // suppressSync is raised while each pane is being sent to its own evidence. Mirroring during
      // that window is what makes one pane land correctly and the other end up wherever the first
      // one's proportion happened to point.
      if (!scrollTogether || syncing.current || suppressSync.current.active || !source || !target) {
        return;
      }
      // Both panes must still be in the document. React does not null a ref when the element it
      // pointed at unmounts on a conditional branch, so after switching view mode these can hold
      // detached nodes — and writing scrollTop to a detached node silently does nothing, which is
      // indistinguishable from synchronized scrolling being broken.
      if (!source.isConnected || !target.isConnected) {
        return;
      }
      const sourceRange = source.scrollHeight - source.clientHeight;
      const targetRange = target.scrollHeight - target.clientHeight;
      if (sourceRange <= 0 || targetRange <= 0) {
        return;
      }
      syncing.current = true;
      target.scrollTop = (source.scrollTop / sourceRange) * targetRange;
      requestAnimationFrame(() => {
        syncing.current = false;
      });
    },
    [scrollTogether],
  );

  const onLeftScroll = useCallback(
    () => synchronize(leftRef.current, rightRef.current),
    [synchronize],
  );
  const onRightScroll = useCallback(
    () => synchronize(rightRef.current, leftRef.current),
    [synchronize],
  );

  const toggleFullscreen = useCallback(async () => {
    try {
      if (!document.fullscreenElement) {
        await documentsAreaRef.current?.requestFullscreen();
      } else {
        await document.exitFullscreen();
      }
    } catch {
      // Denied by the browser (no user gesture, or policy). Reset rather than leaving the control
      // showing a state the document is not in.
      setFullscreen(false);
    }
  }, []);

  // Restore the reading position after a zoom change.
  useEffect(() => {
    const anchors = zoomAnchors.current;
    if (!anchors) {
      return undefined;
    }
    const frame = requestAnimationFrame(() => {
      [leftRef.current, rightRef.current].forEach((element, index) => {
        const anchor = anchors[index];
        if (!element || !anchor) {
          return;
        }
        element.scrollLeft = anchor.x * element.scrollWidth - element.clientWidth / 2;
        element.scrollTop = anchor.y * element.scrollHeight - element.clientHeight / 2;
      });
      zoomAnchors.current = null;
    });
    return () => cancelAnimationFrame(frame);
  }, [zoom]);

  // Track fullscreen changes the user made with Escape or the browser's own control.
  useEffect(() => {
    const handler = () => setFullscreen(document.fullscreenElement === documentsAreaRef.current);
    document.addEventListener("fullscreenchange", handler);
    return () => document.removeEventListener("fullscreenchange", handler);
  }, []);

  // Re-fit on resize while in fit-width mode.
  useEffect(() => {
    if (viewMode !== "fit-width") {
      return undefined;
    }
    const frame = requestAnimationFrame(fitWidth);
    const onResize = () => requestAnimationFrame(fitWidth);
    window.addEventListener("resize", onResize);
    return () => {
      cancelAnimationFrame(frame);
      window.removeEventListener("resize", onResize);
    };
  }, [viewMode, fitWidth]);

  return {
    zoom,
    viewMode,
    scrollTogether,
    fullscreen,
    leftRef,
    rightRef,
    documentsAreaRef,
    suppressSync,
    setScrollTogether,
    zoomIn,
    zoomOut,
    fitWidth,
    toggleFullscreen,
    onLeftScroll,
    onRightScroll,
  };
}

/**
 * How long synchronized scrolling stays suppressed after an auto-scroll begins.
 *
 * Long enough to cover a smooth scroll to completion. Too short and sync resumes mid-animation,
 * mirroring a half-finished position onto the other pane and undoing it; too long and the reader's
 * own next scroll is briefly unsynced. Browsers do not expose a "smooth scroll finished" event, so
 * this is a duration rather than a callback.
 */
const AUTO_SCROLL_SETTLE_MS = 700;

/**
 * Scrolls each pane to its own copy of the active finding's evidence.
 *
 * <p><strong>Each pane is scrolled independently, and synchronized scrolling is suspended while it
 * happens.</strong> The cited sentence sits at a different relative position in each document — 10%
 * into the process, 70% into the standard — so mirroring one pane's position onto the other is
 * exactly wrong here. Without the suspension, the first pane's `scrollTo` fires a scroll event that
 * overwrites the second pane's target, and the reader has to hunt for the highlight in whichever
 * pane lost the race.
 *
 * <p>Targets `[data-finding-id]`, which {@code CanonicalHtmlViewer} sets only on a mark it actually
 * drew. A pane with no resolvable highlight stays where it is rather than jumping to an arbitrary
 * position — that correctly signals "nothing to show here" instead of implying the evidence is
 * wherever the pane happened to land.
 */
export function useScrollToFinding(
  findingId: string | null | undefined,
  panes: readonly React.RefObject<HTMLDivElement | null>[],
  suppression: React.RefObject<ScrollSuppression>,
  deps: readonly unknown[] = [],
) {
  useEffect(() => {
    if (!findingId) {
      return undefined;
    }

    let settleTimer: ReturnType<typeof setTimeout> | undefined;

    const frame = requestAnimationFrame(() => {
      // Raised BEFORE the first scrollTo, because the scroll event it triggers would otherwise
      // reach `synchronize` before the second pane has been positioned.
      suppression.current.active = true;

      let scrolled = false;
      panes.forEach((pane) => {
        const container = pane.current;
        // isConnected guards the stale-ref case: a ref left pointing at an unmounted pane would
        // measure rectangles from a detached node, producing an offset computed against a layout
        // that is not on screen.
        if (!container || !container.isConnected) {
          return;
        }
        const highlight = container.querySelector(`[data-finding-id="${findingId}"]`);
        if (!highlight) {
          return;
        }
        const containerRect = container.getBoundingClientRect();
        const highlightRect = highlight.getBoundingClientRect();
        container.scrollTo({
          top:
            container.scrollTop +
            highlightRect.top -
            containerRect.top -
            container.clientHeight / 2 +
            highlightRect.height / 2,
          behavior: "smooth",
        });
        scrolled = true;
      });

      if (!scrolled) {
        // Nothing moved, so nothing needs settling. Releasing immediately keeps the reader's own
        // scrolling synchronized when a finding resolves in neither pane.
        suppression.current.active = false;
        return;
      }

      settleTimer = setTimeout(() => {
        suppression.current.active = false;
      }, AUTO_SCROLL_SETTLE_MS);
    });

    return () => {
      cancelAnimationFrame(frame);
      if (settleTimer !== undefined) {
        clearTimeout(settleTimer);
      }
      // Never leave sync suppressed because the effect was torn down mid-scroll — that would
      // silently disable the feature for the rest of the session.
      suppression.current.active = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [findingId, ...deps]);
}

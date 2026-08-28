/**
 * API client. Architecture §7.4.
 *
 * Same-origin relative paths only. The browser never receives LiteLLM, MongoDB, MinIO, or Qdrant
 * credentials — it talks to review-core, which is the sole public surface (§3).
 */
export const API_BASE_URL = "/api";

/** Structured API error, mirroring review-core's ErrorResponse record (§21). */
export class ApiError extends Error {
  constructor(
    /** Stable enumerated code. Branch on THIS, never on the message: messages get reworded. */
    readonly code: string,
    message: string,
    readonly status: number,
    /** Correlates a user-reported error with a server log line without leaking internals. */
    readonly correlationId?: string,
    readonly details: readonly string[] = [],
  ) {
    super(message);
    this.name = "ApiError";
  }
}

interface ProblemResponse {
  code?: string;
  message?: string;
  correlationId?: string;
  details?: string[];
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  // FormData must NOT carry an explicit Content-Type. Only the browser can write the
  // `multipart/form-data; boundary=…` value, because only it knows the generated boundary — setting
  // the header ourselves produces a body the server cannot split into parts.
  //
  // Testing `init.body` alone is not enough: FormData IS truthy, so the previous version stamped
  // `application/json` onto every upload and the server rejected it with
  // "Content-Type 'application/json' is not supported" — a 500 that names JSON while the request was
  // actually a file upload.
  const isMultipart = init?.body instanceof FormData;

  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...init,
    headers: {
      Accept: "application/json",
      ...(init?.body && !isMultipart ? { "Content-Type": "application/json" } : {}),
      ...init?.headers,
    },
    // Demo mode uses HTTP Basic (§33); the browser supplies the session automatically.
    credentials: "same-origin",
  });

  if (!response.ok) {
    // Parse the problem body when present. A server that returns HTML (a proxy error page, say)
    // must not crash the client with a JSON parse failure.
    let problem: ProblemResponse = {};
    try {
      problem = (await response.json()) as ProblemResponse;
    } catch {
      /* non-JSON error body */
    }
    throw new ApiError(
      problem.code ?? "UNKNOWN",
      problem.message ?? `Request failed with status ${response.status}`,
      response.status,
      problem.correlationId,
      problem.details ?? [],
    );
  }

  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

/**
 * Builds a RequestInit, OMITTING `body` rather than setting it to undefined.
 *
 * Under `exactOptionalPropertyTypes` those are different types: `{body: undefined}` does not satisfy
 * `{body?: BodyInit | null}`. The distinction is not pedantry — an explicitly-undefined property is
 * observably present via `in` and `Object.keys`, and a fetch polyfill that checks for the key
 * behaves differently from one that checks the value.
 */
function withBody(method: string, body?: unknown): RequestInit {
  return body === undefined
    ? { method }
    : { method, body: JSON.stringify(body) };
}

export const apiClient = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body?: unknown) => request<T>(path, withBody("POST", body)),
  put: <T>(path: string, body?: unknown) => request<T>(path, withBody("PUT", body)),
  patch: <T>(path: string, body?: unknown) => request<T>(path, withBody("PATCH", body)),

  /**
   * Multipart upload.
   *
   * No headers passed: `request` detects FormData and omits Content-Type so the browser can set
   * `multipart/form-data` WITH its generated boundary. An empty `headers: {}` here does not achieve
   * that — spreading it cannot remove a key the caller never sees.
   */
  upload: <T>(path: string, formData: FormData) =>
    request<T>(path, { method: "POST", body: formData }),
};

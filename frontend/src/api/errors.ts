// Error model of the API layer (design D4, spec api-access).
// Every non-2xx answer and every network failure becomes an ApiError.

export type ApiErrorKind =
  | 'validation'
  | 'unauthorized'
  | 'forbidden'
  | 'not-found'
  | 'conflict'
  | 'unprocessable'
  | 'payment'
  | 'unavailable'
  | 'network'
  | 'unexpected';

/** RFC 9457 problem details as answered by the gateway and every service. */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  /** `400` field errors: field name → messages. */
  errors?: Record<string, string[]>;
  [extension: string]: unknown;
}

export class ApiError extends Error {
  readonly kind: ApiErrorKind;
  /** HTTP status; absent for network failures. */
  readonly status?: number;
  /** Parsed problem details; absent when the body was empty or not JSON. */
  readonly problem?: ProblemDetail;
  /** Seconds to wait, from a `Retry-After` header. */
  readonly retryAfter?: number;

  constructor(
    kind: ApiErrorKind,
    options: { status?: number; problem?: ProblemDetail; retryAfter?: number } = {},
  ) {
    super(options.problem?.detail ?? `${kind}${options.status ? ` (${options.status})` : ''}`);
    this.name = 'ApiError';
    this.kind = kind;
    this.status = options.status;
    this.problem = options.problem;
    this.retryAfter = options.retryAfter;
  }
}

export function kindForStatus(status: number): ApiErrorKind {
  switch (status) {
    case 400:
      return 'validation';
    case 401:
      return 'unauthorized';
    case 402:
      return 'payment';
    case 403:
      return 'forbidden';
    case 404:
      return 'not-found';
    case 409:
      return 'conflict';
    case 422:
    case 424:
      return 'unprocessable';
    case 502:
    case 503:
    case 504:
      return 'unavailable';
    default:
      return 'unexpected';
  }
}

/** Parses `Retry-After` (delta seconds or an HTTP date) into whole seconds. */
export function parseRetryAfter(
  value: string | null,
  now: number = Date.now(),
): number | undefined {
  if (!value) return undefined;
  const trimmed = value.trim();
  if (/^\d+$/.test(trimmed)) return Number(trimmed);
  const date = Date.parse(trimmed);
  if (Number.isNaN(date)) return undefined;
  return Math.max(0, Math.ceil((date - now) / 1000));
}

/** Reads a problem-details body; returns undefined for empty or non-JSON bodies. */
export function parseProblem(text: string): ProblemDetail | undefined {
  if (!text) return undefined;
  try {
    const value: unknown = JSON.parse(text);
    return value !== null && typeof value === 'object' && !Array.isArray(value)
      ? (value as ProblemDetail)
      : undefined;
  } catch {
    return undefined;
  }
}

export const MESSAGES = {
  network: 'Cannot reach VoltStore. Check your connection and try again.',
  unavailable: 'This service is temporarily unavailable.',
  forbidden: 'You do not have access to this action.',
  unexpected: 'Something went wrong. Please try again.',
  validation: 'Some of the entered values are not valid.',
  notFound: 'The requested item was not found.',
  conflict: 'This conflicts with the current state. Reload and try again.',
  unprocessable: 'The request could not be processed.',
  unauthorized: 'Please log in to continue.',
  payment: 'The payment was not accepted.',
} as const;

/** The message to show the user for any error thrown by the API layer. */
export function userMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return MESSAGES.unexpected;
  switch (error.kind) {
    case 'network':
      return MESSAGES.network;
    case 'unavailable':
      return error.retryAfter !== undefined
        ? `${MESSAGES.unavailable} Try again in ${formatWait(error.retryAfter)}.`
        : `${MESSAGES.unavailable} Please try again in a moment.`;
    case 'forbidden':
      return MESSAGES.forbidden;
    case 'unexpected':
      // Spec api-access, Unexpected errors: never show technical details.
      return MESSAGES.unexpected;
    case 'validation':
      return error.problem?.detail ?? MESSAGES.validation;
    case 'not-found':
      return error.problem?.detail ?? MESSAGES.notFound;
    case 'conflict':
      return error.problem?.detail ?? MESSAGES.conflict;
    case 'unprocessable':
      return error.problem?.detail ?? MESSAGES.unprocessable;
    case 'payment':
      return error.problem?.detail ?? MESSAGES.payment;
    case 'unauthorized':
      return error.problem?.detail ?? MESSAGES.unauthorized;
  }
}

function formatWait(seconds: number): string {
  if (seconds < 60) return `${seconds} second${seconds === 1 ? '' : 's'}`;
  const minutes = Math.ceil(seconds / 60);
  return `${minutes} minute${minutes === 1 ? '' : 's'}`;
}

/** Network failures and 502/503/504: the only errors a read may be retried on (design D3). */
export function isTransient(error: unknown): boolean {
  return error instanceof ApiError && (error.kind === 'network' || error.kind === 'unavailable');
}

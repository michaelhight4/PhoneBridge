/** Error codes shared by the client library, CLI and MCP server. */
export type PhoneBridgeErrorCode =
  | "permission_denied"
  | "not_controller"
  | "busy"
  | "bad_args"
  | "unknown_cmd"
  | "peer_offline"
  | "accessibility_disabled"
  | "projection_denied"
  | "shizuku_unavailable"
  | "timeout"
  | "internal"
  | "bad_code"
  | "bad_offer"
  | "expired"
  | "not_paired"
  | "no_device"
  | "ambiguous_device"
  | "handshake_failed"
  | "relay_error"
  | "bad_input"
  | "unsupported";

export class PhoneBridgeError extends Error {
  readonly code: PhoneBridgeErrorCode;
  readonly details: Record<string, unknown> | undefined;

  constructor(code: PhoneBridgeErrorCode, message: string, details?: Record<string, unknown>) {
    super(message);
    this.name = "PhoneBridgeError";
    this.code = code;
    this.details = details;
  }

  toJSON(): { code: string; message: string; details?: Record<string, unknown> } {
    return this.details === undefined
      ? { code: this.code, message: this.message }
      : { code: this.code, message: this.message, details: this.details };
  }
}

export function isPhoneBridgeError(value: unknown): value is PhoneBridgeError {
  return value instanceof PhoneBridgeError;
}

const REMOTE_CODES = new Set<string>([
  "permission_denied",
  "not_controller",
  "busy",
  "bad_args",
  "unknown_cmd",
  "peer_offline",
  "accessibility_disabled",
  "projection_denied",
  "shizuku_unavailable",
  "timeout",
  "internal",
]);

/** Maps a remote `{code,message}` error object from the device onto a PhoneBridgeError. */
export function remoteError(error: unknown): PhoneBridgeError {
  if (isPhoneBridgeError(error)) return error;
  if (typeof error === "object" && error !== null) {
    const record = error as Record<string, unknown>;
    const code = typeof record["code"] === "string" ? record["code"] : "internal";
    const message = typeof record["message"] === "string" ? record["message"] : "device error";
    const normalized = REMOTE_CODES.has(code) ? (code as PhoneBridgeErrorCode) : "internal";
    return new PhoneBridgeError(normalized, message, {
      remoteCode: code,
      ...(typeof record["details"] === "object" && record["details"] !== null
        ? { remoteDetails: record["details"] }
        : {}),
    });
  }
  return new PhoneBridgeError("internal", String(error ?? "device error"));
}

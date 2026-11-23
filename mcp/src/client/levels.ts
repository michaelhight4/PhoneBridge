/** Permission levels from docs/SPEC.md. Order matters: view < control < full. */
import type { PermissionLevel } from "./keystore.js";
import { PhoneBridgeError } from "./errors.js";

export type { PermissionLevel } from "./keystore.js";

const ORDER: Record<PermissionLevel, number> = { view: 0, control: 1, full: 2 };

export function isPermissionLevel(value: unknown): value is PermissionLevel {
  return value === "view" || value === "control" || value === "full";
}

export function normalizeLevel(value: unknown, fallback: PermissionLevel = "view"): PermissionLevel {
  if (isPermissionLevel(value)) return value;
  if (typeof value === "number") {
    if (value >= 3) return "full";
    if (value >= 2) return "control";
    return "view";
  }
  return fallback;
}

export function levelRank(level: PermissionLevel): number {
  return ORDER[level];
}

export function levelAtLeast(level: PermissionLevel, required: PermissionLevel): boolean {
  return levelRank(level) >= levelRank(required);
}

/** Required level per protocol command (see the table in docs/PROTOCOL.md). */
export const COMMAND_LEVELS: Record<string, PermissionLevel> = {
  status: "view",
  screenshot: "view",
  ui_tree: "view",
  notifications: "view",
  pair_request: "view",
  tap: "control",
  long_press: "control",
  swipe: "control",
  type: "control",
  key: "control",
  open_app: "control",
  request_control: "control",
  release_control: "control",
  install_apk: "full",
  shell: "full",
  files: "full",
  settings: "full",
  relay_move: "full",
};

export function requiredLevel(cmd: string): PermissionLevel {
  return COMMAND_LEVELS[cmd] ?? "full";
}

/** Throws locally when the paired device did not grant enough permission. */
export function assertLevel(granted: PermissionLevel, cmd: string): void {
  const required = requiredLevel(cmd);
  if (!levelAtLeast(granted, required)) {
    throw new PhoneBridgeError(
      "permission_denied",
      `"${cmd}" needs ${required} permission but this bot has ${granted}`,
      { required, granted },
    );
  }
}

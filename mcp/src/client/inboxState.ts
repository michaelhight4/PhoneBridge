/**
 * Small local cache of bot inbox state: the most recent received message and a bounded map of recent
 * ids, used by `pb reply` to pick a target and a `reply_to` parent. Stored beside the keystore in
 * `$PHONEBRIDGE_HOME/inbox-state.json` with the same atomic temp+rename write pattern.
 */
import { chmod, mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { defaultHome } from "./keystore.js";
import { PhoneBridgeError } from "./errors.js";

export const MAX_RECENT_INBOX_MESSAGES = 200;

/** One received bot message, in the shape needed to answer it. */
export interface InboxStateMessage {
  id: string;
  from: string;
  fromName?: string;
  deviceId?: string;
  text?: string;
  createdAt: number;
}

export interface InboxStateData {
  version: 1;
  lastReceived?: InboxStateMessage;
  recent: Record<string, InboxStateMessage>;
}

export function inboxStatePath(home?: string): string {
  return join(home ?? defaultHome(), "inbox-state.json");
}

export class InboxStateStore {
  readonly home: string;
  readonly path: string;
  private last: InboxStateMessage | undefined;
  private readonly recent = new Map<string, InboxStateMessage>();
  private dirty = false;

  private constructor(home: string, last: InboxStateMessage | undefined, recent: Map<string, InboxStateMessage>) {
    this.home = home;
    this.path = inboxStatePath(home);
    this.last = last;
    for (const [id, message] of recent) this.recent.set(id, message);
    this.trim();
  }

  static async open(options: { home?: string } = {}): Promise<InboxStateStore> {
    const home = options.home ?? defaultHome();
    const path = inboxStatePath(home);
    let raw: string | null = null;
    try {
      raw = await readFile(path, "utf8");
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
    }
    if (raw === null) return new InboxStateStore(home, undefined, new Map());

    let parsed: InboxStateData;
    try {
      parsed = JSON.parse(raw) as InboxStateData;
    } catch {
      throw new PhoneBridgeError("bad_input", `inbox state at ${path} is not valid JSON`);
    }
    if (parsed.version !== 1 || typeof parsed.recent !== "object" || parsed.recent === null) {
      throw new PhoneBridgeError("bad_input", `inbox state at ${path} is not a PhoneBridge v1 inbox state`);
    }
    const recent = new Map<string, InboxStateMessage>();
    for (const [id, value] of Object.entries(parsed.recent)) {
      if (messageId(value) === id) recent.set(id, value);
    }
    const last = validMessage(parsed.lastReceived) ? parsed.lastReceived : undefined;
    return new InboxStateStore(home, last, recent);
  }

  get lastReceived(): InboxStateMessage | undefined {
    return this.last === undefined ? undefined : { ...this.last };
  }

  /** Recent messages, oldest first. */
  all(): InboxStateMessage[] {
    return [...this.recent.values()].map((message) => ({ ...message }));
  }

  findById(id: string): InboxStateMessage | undefined {
    const message = this.recent.get(id);
    return message === undefined ? undefined : { ...message };
  }

  /** Most recently stored message from `peerId`, or undefined. */
  latestFrom(peerId: string): InboxStateMessage | undefined {
    const wanted = peerId.toLowerCase();
    for (const message of [...this.recent.values()].reverse()) {
      if (message.from.toLowerCase() === wanted) return { ...message };
    }
    return undefined;
  }

  /** Records a received message as both the newest and a recent entry. */
  note(message: InboxStateMessage): InboxStateMessage {
    const stored: InboxStateMessage = { ...message };
    this.last = stored;
    // Re-insert so iteration order follows receipt order for the bounded trim.
    this.recent.delete(stored.id);
    this.recent.set(stored.id, stored);
    this.trim();
    this.dirty = true;
    return { ...stored };
  }

  async save(): Promise<void> {
    await mkdir(dirname(this.path), { recursive: true });
    const temp = `${this.path}.tmp`;
    await writeFile(temp, `${JSON.stringify(this.toJSON(), null, 2)}\n`, { encoding: "utf8", mode: 0o600 });
    await rename(temp, this.path);
    try {
      await chmod(this.path, 0o600);
    } catch {
      /* Windows ignores POSIX modes; the file is still per-user under the profile directory. */
    }
    this.dirty = false;
  }

  async flush(): Promise<void> {
    if (this.dirty) await this.save();
  }

  toJSON(): InboxStateData {
    return {
      version: 1,
      ...(this.last === undefined ? {} : { lastReceived: { ...this.last } }),
      recent: Object.fromEntries([...this.recent].map(([id, message]) => [id, { ...message }])),
    };
  }

  private trim(): void {
    while (this.recent.size > MAX_RECENT_INBOX_MESSAGES) {
      const oldest = this.recent.keys().next().value;
      if (oldest === undefined) return;
      this.recent.delete(oldest);
    }
  }
}

function validMessage(value: unknown): value is InboxStateMessage {
  if (typeof value !== "object" || value === null) return false;
  const message = value as Record<string, unknown>;
  return messageId(message) !== undefined && typeof message["from"] === "string";
}

function messageId(value: unknown): string | undefined {
  if (typeof value !== "object" || value === null) return undefined;
  const id = (value as Record<string, unknown>)["id"];
  return typeof id === "string" && id !== "" ? id : undefined;
}

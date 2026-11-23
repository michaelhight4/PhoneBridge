/**
 * Local JSON keystore: the bot's long-term X25519 identity plus every paired device.
 *
 * Location: `$PHONEBRIDGE_HOME` if set, else `~/.phonebridge/keystore.json` (mode 0600).
 * Nothing secret leaves this file, and it never contains the one-time pairing secret.
 */
import { chmod, mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { homedir } from "node:os";
import { dirname, join } from "node:path";
import { createIdentity, identityFromSecret, type Identity, peerIdForPublicKey } from "./crypto.js";
import { fingerprintForPeerId } from "./crypto.js";
import { fromBase64Url, toBase64Url } from "./bytes.js";
import { PhoneBridgeError } from "./errors.js";

export type PermissionLevel = "view" | "control" | "full";
export const PERMISSION_LEVELS: readonly PermissionLevel[] = ["view", "control", "full"];

export interface StoredDevice {
  peerId: string;
  name: string;
  publicKey: string;
  relayUrl: string;
  relayFingerprint?: string;
  level: PermissionLevel;
  pairedAt: string;
  lastSeenAt?: string;
  botName?: string;
}

interface StoredIdentity {
  publicKey: string;
  secretKey: string;
  peerId: string;
  createdAt: string;
}

export interface KeystoreData {
  version: 1;
  identity: StoredIdentity;
  devices: StoredDevice[];
  currentDevice?: string;
}

export function defaultHome(): string {
  const fromEnv = process.env["PHONEBRIDGE_HOME"];
  if (fromEnv !== undefined && fromEnv.trim() !== "") return fromEnv;
  return join(homedir(), ".phonebridge");
}

export function keystorePath(home?: string): string {
  return join(home ?? defaultHome(), "keystore.json");
}

export class Keystore {
  readonly home: string;
  readonly path: string;
  private data: KeystoreData;
  private cryptoIdentity: Identity | undefined;
  private dirty = false;

  private constructor(home: string, data: KeystoreData) {
    this.home = home;
    this.path = keystorePath(home);
    this.data = data;
  }

  static async open(options: { home?: string } = {}): Promise<Keystore> {
    const home = options.home ?? defaultHome();
    const path = keystorePath(home);
    let raw: string | null = null;
    try {
      raw = await readFile(path, "utf8");
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
    }

    if (raw === null) {
      const identity = await createIdentity();
      const data: KeystoreData = {
        version: 1,
        identity: {
          publicKey: toBase64Url(identity.publicKey),
          secretKey: toBase64Url(identity.secretKey),
          peerId: identity.peerId,
          createdAt: new Date().toISOString(),
        },
        devices: [],
      };
      const store = new Keystore(home, data);
      store.cryptoIdentity = identity;
      await store.save();
      return store;
    }

    const parsed = JSON.parse(raw) as KeystoreData;
    if (parsed.version !== 1 || typeof parsed.identity?.secretKey !== "string") {
      throw new PhoneBridgeError("bad_input", `keystore at ${path} is not a PhoneBridge v1 keystore`);
    }
    parsed.devices = Array.isArray(parsed.devices) ? parsed.devices : [];
    return new Keystore(home, parsed);
  }

  get rawIdentity(): Identity {
    if (this.cryptoIdentity !== undefined) return this.cryptoIdentity;
    const secret = fromBase64Url(this.data.identity.secretKey);
    const publicKey = fromBase64Url(this.data.identity.publicKey);
    return {
      secretKey: secret,
      publicKey,
      peerId: this.data.identity.peerId ?? peerIdForPublicKey(publicKey),
    };
  }

  async identity(): Promise<Identity> {
    if (this.cryptoIdentity === undefined) {
      const stored = this.rawIdentity;
      this.cryptoIdentity = await identityFromSecret(stored.secretKey);
      if (this.cryptoIdentity.peerId !== this.data.identity.peerId) {
        this.data.identity.peerId = this.cryptoIdentity.peerId;
        this.dirty = true;
      }
    }
    return this.cryptoIdentity;
  }

  get botPeerId(): string {
    return this.data.identity.peerId;
  }

  get botFingerprint(): string {
    return fingerprintForPeerId(this.data.identity.peerId);
  }

  devices(): StoredDevice[] {
    return this.data.devices.map((device) => ({ ...device }));
  }

  /** Resolves a device reference: exact peer_id, peer_id prefix, name, or fingerprint (`ABCD-EFGH`). */
  resolve(ref?: string): StoredDevice {
    const wanted = (ref ?? process.env["PHONEBRIDGE_DEVICE"] ?? "").trim();
    if (wanted === "") {
      const current = this.data.currentDevice;
      if (current !== undefined) {
        const device = this.data.devices.find((entry) => entry.peerId === current);
        if (device !== undefined) return { ...device };
      }
      if (this.data.devices.length === 1) return { ...(this.data.devices[0] as StoredDevice) };
      if (this.data.devices.length === 0) {
        throw new PhoneBridgeError(
          "not_paired",
          "no paired device; run `phonebridge pair <code|link|file>` first",
        );
      }
      throw new PhoneBridgeError(
        "ambiguous_device",
        `several paired devices; pass one of: ${this.describeDevices()}`,
      );
    }

    const lower = wanted.toLowerCase();
    const fingerprint = wanted.replace(/[^0-9a-zA-Z]/g, "").toLowerCase();
    const matches = this.data.devices.filter((device) => {
      if (device.peerId === lower) return true;
      if (device.peerId.startsWith(lower) && lower.length >= 6) return true;
      if (device.name.toLowerCase() === lower) return true;
      return device.peerId.slice(0, 8).toLowerCase() === fingerprint;
    });
    if (matches.length === 0) {
      throw new PhoneBridgeError("no_device", `no paired device matches "${wanted}"`, {
        devices: this.describeDevices(),
      });
    }
    if (matches.length > 1) {
      throw new PhoneBridgeError("ambiguous_device", `"${wanted}" matches several devices`, {
        devices: this.describeDevices(),
      });
    }
    return { ...(matches[0] as StoredDevice) };
  }

  describeDevices(): string {
    return this.data.devices
      .map((device) => `${device.name} (${device.peerId.slice(0, 8)}, ${device.level})`)
      .join(", ");
  }

  upsertDevice(device: StoredDevice): StoredDevice {
    const index = this.data.devices.findIndex((entry) => entry.peerId === device.peerId);
    if (index >= 0) {
      this.data.devices[index] = { ...device };
    } else {
      this.data.devices.push({ ...device });
    }
    this.data.currentDevice = device.peerId;
    this.dirty = true;
    return { ...device };
  }

  removeDevice(peerId: string): boolean {
    const before = this.data.devices.length;
    this.data.devices = this.data.devices.filter((entry) => entry.peerId !== peerId);
    if (this.data.currentDevice === peerId) delete this.data.currentDevice;
    this.dirty = this.dirty || this.data.devices.length !== before;
    return this.data.devices.length !== before;
  }

  setCurrent(peerId: string): void {
    this.data.currentDevice = peerId;
    this.dirty = true;
  }

  touchDevice(peerId: string, when = new Date().toISOString()): void {
    const device = this.data.devices.find((entry) => entry.peerId === peerId);
    if (device === undefined) return;
    device.lastSeenAt = when;
    this.dirty = true;
  }

  /** Updates fields of one paired device without changing the current default device. */
  patchDevice(peerId: string, patch: Partial<Omit<StoredDevice, "peerId">>): StoredDevice | undefined {
    const device = this.data.devices.find((entry) => entry.peerId === peerId);
    if (device === undefined) return undefined;
    Object.assign(device, patch);
    this.dirty = true;
    return { ...device };
  }

  /** Persists the keystore if anything changed. */
  async save(): Promise<void> {
    await mkdir(dirname(this.path), { recursive: true });
    const temp = `${this.path}.tmp`;
    await writeFile(temp, `${JSON.stringify(this.data, null, 2)}\n`, { encoding: "utf8", mode: 0o600 });
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

  toJSON(): KeystoreData {
    return {
      version: 1,
      identity: { ...this.data.identity },
      devices: this.devices(),
      ...(this.data.currentDevice === undefined ? {} : { currentDevice: this.data.currentDevice }),
    };
  }
}

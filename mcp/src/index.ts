/**
 * PhoneBridge bot client library.
 *
 * ```ts
 * import { PhoneBridgeClient } from "phonebridge";
 * const client = await PhoneBridgeClient.open({ botName: "my-agent" });
 * await client.pair("7K4-Q9M", { relayUrl: "wss://relay.example:8765" });
 * const status = await client.status();
 * ```
 */
export { PhoneBridgeClient, type ClientOptions, type PairOptions, type PairOutcome } from "./client/client.js";
export {
  DeviceSession,
  targetFromStored,
  type ConnectOptions,
  type DeviceEvent,
  type DeviceTarget,
  type RequestOptions,
} from "./client/session.js";
export {
  Keystore,
  defaultHome,
  keystorePath,
  PERMISSION_LEVELS,
  type KeystoreData,
  type PermissionLevel,
  type StoredDevice,
} from "./client/keystore.js";
export {
  parsePairingInput,
  parseOfferJson,
  offerFromRelayReply,
  encodePairingLink,
  encodePairingFile,
  normalizeRelayUrl,
  OFFER_TTL_MS,
  type PairingOffer,
  type ParsedPairingInput,
  type RawPairingOffer,
} from "./client/pairing.js";
export { parseCode, makeCode, randomCode, isValidCode } from "./client/codes.js";
export { base32Decode, base32Encode } from "./client/base32.js";
export {
  createIdentity,
  identityFromSecret,
  fingerprintForPeerId,
  peerIdForPublicKey,
  type Identity,
  type KeyPair,
} from "./client/crypto.js";
export {
  assertDevicePinned,
  assertReply,
  createInitiatorHello,
  createResponderReply,
  deriveHandshakeKeys,
  buildFinishMessage,
  verifyFinish,
  type HandshakeHello,
  type HandshakeKeys,
  type HandshakeReply,
} from "./client/handshake.js";
export { SecureChannel, ChunkAssembler, sealMessage, MAX_CHUNK_BYTES } from "./client/transport.js";
export { RelayConnection, normalizeFingerprint, type Envelope } from "./client/relay.js";
export { PhoneBridgeError, isPhoneBridgeError, type PhoneBridgeErrorCode } from "./client/errors.js";
export {
  COMMAND_LEVELS,
  assertLevel,
  levelAtLeast,
  normalizeLevel,
  requiredLevel,
} from "./client/levels.js";

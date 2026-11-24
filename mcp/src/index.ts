/**
 * PhoneBridge bot client library.
 *
 * ```ts
 * import { PhoneBridgeClient } from "phonebridge";
 * const client = await PhoneBridgeClient.open({ botName: "my-agent" });
 * await client.pair("7K4-Q9B", { relayUrl: "wss://relay.example:8765" });
 * const status = await client.status();
 * ```
 */
export {
  PhoneBridgeClient,
  type BotPeer,
  type ClientOptions,
  type DecryptedBotMessage,
  type ListPeersOptions,
  type PairOptions,
  type PairOutcome,
  type ReadInboxOptions,
  type ReplyOptions,
  type SendMessageOptions,
  type SentBotMessage,
} from "./client/client.js";
export {
  BOT_MESSAGE_VERSION,
  BOT_MSG_AAD_PREFIX,
  BOT_MSG_HEADER_BYTES,
  BOT_MSG_KEY_INFO_TEXT,
  BOT_MSG_SALT_TEXT,
  openBotMessage,
  sealBotMessage,
  type BotMessagePlaintext,
  type BotMessageRandom,
} from "./client/botMessageCrypto.js";
export {
  InboxStateStore,
  MAX_RECENT_INBOX_MESSAGES,
  inboxStatePath,
  type InboxStateData,
  type InboxStateMessage,
} from "./client/inboxState.js";
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
  encodePairingLink,
  encodePairingFile,
  normalizeRelayUrl,
  OFFER_TTL_MS,
  type PairingOffer,
  type ParsedPairingInput,
  type RawPairingOffer,
} from "./client/pairing.js";
export { parseCode, makeCode, randomCode, isValidCode, formatCode } from "./client/codes.js";
export { base32Decode, base32Encode } from "./client/base32.js";
export {
  createIdentity,
  identityFromSecret,
  fingerprintForPeerId,
  peerIdForPublicKey,
  hkdf,
  hmacSha256,
  noiseNonce,
  sha256,
  type Identity,
  type KeyPair,
} from "./client/crypto.js";
export {
  E2ESession,
  NoiseXXHandshake,
  NoiseTransport,
  frameHandshake,
  frameTransport,
  MAX_PLAINTEXT_BYTES,
  type NoiseRole,
} from "./client/noise.js";
export { ChunkAssembler, buildChunks, CHUNK_SIZE, MAX_PAYLOAD_BYTES, type ChunkPayload } from "./client/chunks.js";
export {
  helloProof,
  helloContext,
  verifyHelloProof,
  HELLO_PROOF_METHOD,
} from "./client/helloAuth.js";
export {
  derivePairingKey,
  openPairingPayload,
  sealPairingPayload,
} from "./client/pairingCrypto.js";
export {
  RelayConnection,
  decodePin,
  type Envelope,
  type RelayBotMessage,
  type RelayInfo,
  type RelayPeerEntry,
} from "./client/relay.js";
export { PhoneBridgeError, isPhoneBridgeError, type PhoneBridgeErrorCode } from "./client/errors.js";
export {
  COMMAND_LEVELS,
  assertLevel,
  levelAtLeast,
  normalizeLevel,
  requiredLevel,
} from "./client/levels.js";

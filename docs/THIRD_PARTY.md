# Third-party components

PhoneBridge only uses permissively licensed code. Add a row when you introduce a dependency, and keep
the versions in sync with the build files.

## mcp/ (bot client, MCP server, CLI)

| Component | Version | Licence | Used for |
| --- | --- | --- | --- |
| [@modelcontextprotocol/sdk](https://github.com/modelcontextprotocol/typescript-sdk) | 1.32.x | MIT | MCP server (stdio + streamable HTTP) and the in-memory client used in tests |
| [libsodium-wrappers](https://github.com/jedisct1/libsodium.js) | 0.8.x | ISC (libsodium) | X25519, keyed BLAKE2b, XChaCha20-Poly1305, random bytes |
| [ws](https://github.com/websockets/ws) | 8.23.x | MIT | WebSocket transport to the relay, plus the in-process mock relay in tests |
| [zod](https://github.com/colinhacks/zod) | 3.25.x | MIT | MCP tool input schemas |
| [typescript](https://github.com/microsoft/TypeScript) | 5.9.x | Apache-2.0 | build only |
| [vitest](https://github.com/vitest-dev/vitest) | 5.1.x | MIT | test runner |
| [@types/node](https://github.com/DefinitelyTyped/DefinitelyTyped), [@types/ws](https://github.com/DefinitelyTyped/DefinitelyTyped) | 24 / 8.18 | MIT | type definitions |

No GPL/AGPL code is used. The MCP server has no runtime dependency on the Android app or the relay.

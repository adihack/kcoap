# kcoap-common

The base every kcoap module builds on: logging, the root of kcoap's exceptions, byte helpers for secrets, and the
two opt-in markers. It has no dependencies, not even kotlinx-coroutines, and is common Kotlin only, so it builds for
every target. kcoap-transport, kcoap-cbor and kcoap-crypto export it (`api`), so an application reaches it through
any kcoap module and rarely names it.

| Package | Type | What it is |
|---|---|---|
| `dev.kcoap.log` | `KcoapLogging`, `LogConfig` | The global logging configuration: a threshold, levels per dot-separated tag, the sink, whether payloads are dumped (off by default), the clock for timestamps. `LogConfig.build { ... }` or `KcoapLogging.configure { ... }` |
| | `LoggerFactory`, `Logger` | What every component that logs takes (`loggerFactory = LoggerFactory.Global`, or a factory of its own for one component); messages are lambdas, evaluated only at an enabled level |
| | `LogSink`, `ConsoleSink`, `RingBufferSink`, `CompositeSink`, `LogFormatter`, `LogRecord` | Where records go: a one-method interface, the console, the latest records in memory for diagnostics, several sinks at once (`sink + other`, `sink.atLeast(level)`) |
| | `LogTags` | The catalogue of tags kcoap logs under (`coap.message`, `oscore`, `edhoc`, `transport`, ...) |
| `dev.kcoap.common` | `KcoapException` | The root of every protocol's exception: `CoapException`, `OscoreException`, `EdhocException` and its subtypes, `CborException`, `CertificateFormatException`. One `catch` handles the runtime failures of any kcoap call, and the public suspend entry points list it in `@Throws` for Swift |
| | `constantTimeEquals`, `ByteArray.wipe()` | Comparison in time that depends only on the lengths, for MACs, tags and other secret-derived values; overwriting a secret once it is no longer needed |
| | `Hex`, `toHex()`, `hexToBytes()` | Lower-case hex, for test vectors and logs |
| | `@ExperimentalKcoapApi` | Marks a published SPI that nothing implements yet (`DatagramSecurity`); using it needs `@OptIn` ([versioning](../docs/versioning.md#api-tiers)) |
| | `@InternalKcoapApi` | Marks plumbing between kcoap's modules (`ByteArrayBuilder`, `minimalUnsignedBytes`, `readUnsigned`, `OptionCodec`, ...); not API, left out of the ABI dumps, and applications must not use it |

## Using it

Logging is configured once, globally, or per component through the `loggerFactory` it takes;
[docs/logging.md](../docs/logging.md) has the levels, the tags, the sinks, and how a sink forwards records to SLF4J,
Logcat or OSLog:

```kotlin
KcoapLogging.configure {
    level = LogLevel.INFO
    level(LogTags.OSCORE, LogLevel.DEBUG)
    sink = ConsoleSink()
}
```

Every protocol's exception extends `KcoapException`, so code that runs EDHOC and then sends requests handles kcoap's
runtime failures in one place, and a mistake of the caller still surfaces as `IllegalArgumentException` or
`IllegalStateException`:

```kotlin
try {
    val (_, protection) = EdhocClientHandshake.over(client, server, initiatorConfig, crypto).runCombined()
    security.install(client, server, protection)
    client.get("coap://192.0.2.1/lights")
} catch (e: KcoapException) {
    when (e) {
        is CoapException -> log.warn { "CoAP failed: ${e.reason}" }
        is EdhocException -> log.warn { "EDHOC failed: ${e.error}" }
        else -> log.warn(e) { "kcoap failed" }
    }
}
```

The signals of the SPIs between kcoap's layers (`MessageSecurityException`, `CoapFormatException`,
`SessionEndedException`, `AeadAuthenticationException`, `UnsupportedAlgorithmException`,
`CertificateValidationException`) do not extend it: each layer translates them at its boundary
([architecture](../docs/architecture.md#the-coap-endpoint-as-built), "Errors").

## Verified by

| Test | What |
|---|---|
| `LoggerTest` | the INFO default, levels inherited along tags and cleared again, OFF, messages not built when disabled, fields and child tags, payloads as hex only when enabled, a sink that throws, a global change reaching existing loggers |
| `SinkTest` | the ring buffer (newest records in order, a capacity it needs), composite and threshold sinks, the formatter with an error, the console sink |
| `BytesTest` | hex both ways and bad hex, `constantTimeEquals`, `wipe`, the unsigned big-endian helpers |
| `KcoapExceptionTest` | a subtype keeps its message and cause, the cause is optional, one `catch` handles every subtype |

They run on every target: the JVM, Linux native, Apple native (on macOS), and Node.js as JavaScript and as Wasm.
That every protocol's exception is a `KcoapException`, and no SPI signal is, is checked where all of them are
visible, by `ErrorModelTest` in kcoap-edhoc-oscore.

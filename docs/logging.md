# Logging

kcoap logs through its own small API in `kcoap-common` (`dev.kcoap.log`), so it runs the same on
the JVM, Android, iOS and in browsers, and so an application decides where records go.

## Configure

The global configuration applies to every component that was not given its own:

```kotlin
KcoapLogging.configure {
    level = LogLevel.INFO                    // default threshold
    level(LogTags.OSCORE, LogLevel.DEBUG)    // more detail for one protocol
    level(LogTags.COAP_MESSAGE, LogLevel.TRACE)
    payloadDumps = false                     // message bodies as lengths only (the default)
    sink = ConsoleSink()
}
```

Levels are inherited along dot-separated tags: a level for `coap` covers `coap.exchange` and
`coap.message` unless they have their own. Changes apply immediately, also to loggers created earlier.

| Tag (`LogTags`) | What logs there |
|---|---|
| `coap.message` | encoded and decoded messages (TRACE) |
| `coap.exchange` | requests, responses, retransmissions, timeouts |
| `coap.client`, `coap.server` | client and server API events |
| `transport` | sockets and connections |
| `oscore` (`oscore.client`, `oscore.server`) | protection, verification, rejected messages, freshness challenges |
| `edhoc` | handshake steps and failures |
| `dtls` | DTLS sessions |
| `crypto` | backend selection and failures |

| Level | Used for |
|---|---|
| TRACE | wire bytes and state transitions |
| DEBUG | protocol events a developer follows |
| INFO | lifecycle: endpoints started, sessions established |
| WARN | rejected input: replays, failed verification, malformed messages |
| ERROR | failures the library could not handle |

Key material, nonces and plaintexts are never logged. Payloads appear only as their length unless
`payloadDumps` is switched on, and then as hex.

## Gather

A sink is a one-method interface, so records can go anywhere:

```kotlin
val recent = RingBufferSink(capacity = 2_000)          // keeps the latest records in memory
KcoapLogging.configure {
    sink = ConsoleSink() +                               // fan out to several sinks
        recent +
        crashReporter.atLeast(LogLevel.WARN) +           // only warnings and errors
        LogSink { record -> myLogger.log(record.level.name, record.toString()) }
}

// Later, for a bug report or a diagnostics screen:
val text: String = recent.dump()
val records: List<LogRecord> = recent.snapshot()
```

`LogRecord` carries the timestamp, level, tag, message, structured fields and the exception, so a
sink can forward them to SLF4J, Logcat, OSLog or a JSON pipeline without parsing text.

## Per component

Every component takes a `LoggerFactory`; by default it follows the global configuration. Give one
component its own to trace it alone:

```kotlin
val traceOneDevice = LoggerFactory(LogConfig.build { level = LogLevel.TRACE; sink = RingBufferSink(500) })
val client = OscoreClient(context, loggers = traceOneDevice)
```

Inside kcoap, loggers carry context as fields instead of message text:

```kotlin
private val log = loggers.logger(LogTags.COAP_EXCHANGE)
val exchangeLog = log.with("peer" to peer, "token" to token.toHex())
exchangeLog.debug { "Retransmitting, attempt $attempt" }
```

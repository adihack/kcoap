# Logging

kcoap logs through its own small API in `kcoap-common` (`dev.kcoap.log`), so it runs the same on
the JVM, Android, iOS and in browsers, and so an application decides where records go.

## Configure

Without any configuration kcoap prints WARN and ERROR records to the console (`LogConfig.Default`): what an operator
should act on and what the library could not handle, but no lifecycle or protocol detail, so an Android or iOS app
that never configures logging writes nothing to its standard output while all is well, and a failure still shows.
Silence it with `level = LogLevel.OFF` or `sink = LogSink.None`; ask for more by lowering the level.

The global configuration applies to every component that was not given its own:

```kotlin
KcoapLogging.configure {
    level = LogLevel.INFO                    // lifecycle too; the default threshold is WARN
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
| DEBUG | protocol events a developer follows, and input from a peer that was rejected or ignored: malformed messages, datagrams from UDP source port 0, which no answer reaches, invalid ACKs and Resets, responses and requests that failed verification, replays, requests an access policy refused, Aborts sent |
| INFO | lifecycle: endpoints started and closed, servers started and stopped, sessions established, connections released or aborted by the peer |
| WARN | local conditions an operator should act on: a send the network refused, a configuration an RFC advises against |
| ERROR | failures the library could not handle: a handler, listener, interceptor or application callback that threw, a transport that failed |

### What the levels follow

- **Input from a peer is DEBUG, without a stack trace.** A UDP source address proves nothing (RFC 7252 §11.4), so
  anyone can send what a node rejects, at network rate; at WARN with a stack trace, every such datagram would cost a
  line in the log and the time to write it. kcoap-coap reports each rejection as an `EndpointEvent` instead
  (`MalformedMessage`, `MessageRejected`, `ResponseRejected`, `SecurityRejected`, `LimitReached`), which is the place to
  count them; the log line is for following one exchange.
- **One line per event.** The layer that decides what happens to a rejected message logs it, once: the messaging
  layer for what it drops or resets, the client's exchange for a response it drops, the server for a request it
  refuses. A layer that throws does not log the same failure too, except at DEBUG to add what its caller cannot know
  ([code-style.md](code-style.md#logging): log or throw, never both).
- **Resource bounds are events, not lines.** What a bound of the endpoint refuses is `EndpointEvent.LimitReached`
  and a DEBUG line, since a flood that reaches a bound would otherwise fill the log at the same rate.
- **A stack trace means a fault on this side**: an exception of a handler, listener, interceptor or callback, or a
  bug, logged at ERROR with its exception.

kcoap-coap follows these rules. kcoap-oscore and kcoap-edhoc-oscore still log some rejected input at WARN, such as a
request or response that failed OSCORE verification, a second time next to kcoap-coap's DEBUG line; they move to the
same rules in a later change ([checklist](checklist.md), architecture-3).

A sink is called on the thread that logs, from inside the endpoint's processing, so it must be quick and must not
block: hand the record to the platform's logger or a queue. With the rules above that happens under load only when
DEBUG is switched on.

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
val client = OscoreClient(context, loggerFactory = traceOneDevice)
```

Inside kcoap, loggers carry context as fields instead of message text:

```kotlin
private val log = loggers.logger(LogTags.COAP_EXCHANGE)
val exchangeLog = log.with("peer" to peer, "token" to token.toHex())
exchangeLog.debug { "Retransmitting, attempt $attempt" }
```

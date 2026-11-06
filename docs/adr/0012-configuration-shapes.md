# 0012. Builders for the configurations that grow, named arguments elsewhere

Date: 2026-11-06. Status: accepted. Settled by WP-B1 and WP-D1 of [target-architecture.md](../target-architecture.md)
(§3.3, §3.5; finding API-06); written down by WP-C3.

## Context

Adding a parameter with a default value to a constructor keeps source compatibility but breaks compiled callers,
and Swift sees no default arguments at all. Data classes make every property part of `copy` and `componentN`, so a
field added later breaks callers, and their generated equality becomes the matching rule whether or not anyone
meant it. The review proposed a builder for every configuration and an endpoint-construction DSL; the draft design
also had the DSL as a third way to make an endpoint.

## Decision

- **Builders only where growth is certain.** `CoapEndpointConfig` and `CoapClientConfig` follow `LogConfig`'s
  pattern: a `Builder` with mutable properties, `toBuilder()`, `Default`, and a DSL factory named after the class
  (`CoapClientConfig { requestTimeout = 5.seconds }`). A new option is a new builder property, an addition in the
  ABI dumps, and Swift sets the options it needs one by one. Values are validated when the configuration is built.
- **Every other configuration keeps a named-argument constructor**, validated in `init` with messages that cite the
  RFC. When one gains a parameter, the previous signature stays as a `@Deprecated(level = HIDDEN)` overload, which
  the ABI check holds in place. RFC-fixed parameter sets (`TransmissionParameters`, `SecurityContextParameters`)
  are of this kind.
- **No endpoint DSL.** The merged named-argument constructors stay the way to make an endpoint
  (`CoapEndpoint(scope, config, transport, connectors, listeners)` and the `CoapClient` and `CoapServer`
  shortcuts the guide uses); a DSL would be a third way, and its trailing lambda would mean something else on
  `CoapServer`, where it builds the resource tree.
- **`Endpoint` variants are final classes** with their equality written out as the matching rule of RFC 7252 §5.3.2
  and RFC 8323 §8 (`Udp`, `Tcp` with its TLS flag, `WebSocket` in RFC 7252 §6.6 normal form, `Opaque`, and
  `Secure` by DTLS session and epoch), not data classes. Data classes whose shape an RFC fixes (`CborValue`,
  `KeyExchangeAlgorithm`) stay as they are.

## Consequences

- The two configurations' constructors are deprecated, not removed, so work in flight keeps compiling with a
  warning; docs/releasing.md removes them before 0.1.0, so no release ships API that is already on its way out.
- Swift builds the two configurations with their builders; the other configurations still take every argument from
  Swift until the Apple facade (roadmap E4).
- Two ways remain to configure: a builder for two classes, a constructor for the rest. The rule for which one a new
  configuration gets is this record: a builder only when it is certain to grow.

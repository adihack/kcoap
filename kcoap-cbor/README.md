# kcoap-cbor

CBOR (RFC 8949) for the protocols in kcoap: a small value model (`CborValue`), a streaming
`CborWriter` that always produces preferred, definite-length encodings, and a `CborReader` for
structures and CBOR sequences (RFC 8742) such as EDHOC messages.

- Deterministic output: shortest argument encodings, definite lengths, map entries in the given
  order (`CborMap.sortedDeterministically()` applies RFC 8949 §4.2.1 key order when needed).
- Strict input: indefinite lengths, floats, reserved encodings, invalid UTF-8, truncation and nesting
  beyond a limit are rejected with `CborException`, which carries the byte offset. It is a `KcoapException`
  (kcoap-common), the root of every protocol's exception in kcoap; COSE (kcoap-cose) throws it too.
- `CborReader.readEncodedItem()` returns an item's exact received bytes, for transcript hashes.

Floats are deliberately unsupported: no CoAP, OSCORE, EDHOC or COSE structure here uses them.

Vectors: RFC 8949 Appendix A (all examples without floats or integers beyond the Long range).

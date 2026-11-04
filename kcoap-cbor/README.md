# kcoap-cbor

Deterministic CBOR encoding (RFC 8949 §4.2) and CBOR sequences (RFC 8742).

EDHOC transcript hashes are computed over exact bytes, so the encoder must produce preferred
serialization with no indefinite lengths, and the decoder must reject non-deterministic input where
RFC 9528 requires it. A small hand-written reader/writer is planned instead of kotlinx-serialization,
which is awkward for byte-exact sequences.

Vectors: RFC 8949 Appendix A.

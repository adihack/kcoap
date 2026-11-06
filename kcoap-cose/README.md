# kcoap-cose

The COSE (RFC 9052 / 9053) pieces OSCORE and EDHOC need. Neither protocol sends complete COSE
messages, so this module provides the structures they authenticate and the key formats they carry:

- `CoseStructures.encStructure` (COSE_Encrypt0 AAD, used by OSCORE §5.4 and EDHOC §5.3.2) and
  `sigStructure` (COSE_Sign1, EDHOC signature methods).
- `CoseHeader`: header labels, including `kccs`, `kcwt`, `x5chain` and `x5t`.
- `CoseKey`: public OKP, EC2 and AKP (ML-DSA) keys, convertible to the raw form the crypto backend
  takes. An EC2 key on P-256 or P-384 may give y as a sign bit (RFC 9053 §7.1.1); kcoap-crypto's `Sec1`
  computes the coordinate.
- `CwtClaimsSet`: the CCS credential of EDHOC, keeping the exact received bytes.

Vectors: RFC 8613 Appendix C (AAD), RFC 9529 (credentials; its PK_R for the sign bit).

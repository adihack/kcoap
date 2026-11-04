# kcoap-edhoc-oscore

Glue between EDHOC and OSCORE: EDHOC over CoAP (RFC 9528 Appendix A.2), mapping of EDHOC connection
identifiers to OSCORE Sender/Recipient IDs and the exporter-based context (Appendix A.1), and the
RFC 9668 combined EDHOC + OSCORE request (EDHOC option, payload split, application-profile parameters).

Interop: aiocoap (combined flow), our libcoap + libedhoc stack.

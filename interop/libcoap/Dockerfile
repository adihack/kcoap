# libcoap with OSCORE and OpenSSL (DTLS/TLS), used as the CoAP/OSCORE reference peer.
#
#   docker build -t kcoap/libcoap interop/libcoap
#   docker run --rm --network host kcoap/libcoap coap-server -v 7
#
# The examples land in /usr/local/bin: coap-client, coap-server, coap-rd, and oscore_testcases.sh's
# companions. LIBCOAP_REF pins the version every interop run uses.

FROM ubuntu:24.04

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        autoconf automake build-essential ca-certificates git libssl-dev libtool pkg-config \
    && rm -rf /var/lib/apt/lists/*

ARG LIBCOAP_REF=v4.3.5b
RUN git clone --depth 1 --branch "${LIBCOAP_REF}" https://github.com/obgm/libcoap.git /src/libcoap

WORKDIR /src/libcoap
RUN ./autogen.sh \
    && ./configure \
        --enable-oscore --enable-tcp --enable-websockets --enable-server-mode --enable-client-mode \
        --enable-proxy-code --with-openssl --enable-examples \
        --disable-doxygen --disable-manpages --enable-tests=no \
    && make -j"$(nproc)" \
    && make install \
    && ldconfig \
    && cp examples/oscore_testcases.sh examples/*.conf /usr/local/share/ 2>/dev/null || true

WORKDIR /work
CMD ["coap-server", "-v", "7"]

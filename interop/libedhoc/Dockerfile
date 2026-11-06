# libedhoc (EDHOC, RFC 9528) built from source with its vendored dependencies (mbedTLS, liboqs for
# ML-KEM/ML-DSA, XKCP for SHAKE256, zcbor), used as the EDHOC reference peer for kcoap.
#
#   docker build -t kcoap/libedhoc:0363bd3 interop/libedhoc
#   docker run --rm kcoap/libedhoc:0363bd3 ctest --test-dir /build --output-on-failure
#   docker run -i --rm kcoap/libedhoc:0363bd3 edhoc_peer --role responder ...   (see peer/edhoc_peer.c)
#
# LIBEDHOC_REF pins the commit every interop run uses.

FROM ubuntu:24.04

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        build-essential ca-certificates cmake git ninja-build python3 python3-jinja2 python3-jsonschema \
        libssl-dev xsltproc \
    && rm -rf /var/lib/apt/lists/*

ARG LIBEDHOC_REF=0363bd3af02265e919e888b60ccffb4606b68f16
RUN git clone https://github.com/kamil-kielbasa/libedhoc.git /src/libedhoc \
    && cd /src/libedhoc \
    && git checkout "${LIBEDHOC_REF}" \
    && git submodule update --init --recursive --depth 1

RUN cmake -S /src/libedhoc -B /build -G Ninja -DCMAKE_BUILD_TYPE=Release -DLIBEDHOC_ENABLE_TESTS=ON \
    && cmake --build /build -j"$(nproc)"

# The line-protocol peer for kcoap's interop tests, built inside libedhoc's build tree so it links the pinned
# library and compiles its reference cipher suites like libedhoc's own tests do.
COPY peer /src/kcoap-peer
RUN echo 'add_subdirectory(/src/kcoap-peer ${CMAKE_BINARY_DIR}/kcoap-peer)' >> /src/libedhoc/CMakeLists.txt \
    && cmake -S /src/libedhoc -B /build \
    && cmake --build /build --target edhoc_peer \
    && ln -s /build/kcoap-peer/edhoc_peer /usr/local/bin/edhoc_peer

WORKDIR /build
CMD ["ctest", "--output-on-failure"]

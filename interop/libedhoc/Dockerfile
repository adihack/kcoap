# libedhoc (EDHOC, RFC 9528) built from source with its vendored dependencies (mbedTLS, liboqs for
# ML-KEM/ML-DSA, XKCP for SHAKE256, zcbor), used as the EDHOC reference peer for kcoap.
#
#   docker build -t kcoap/libedhoc interop/libedhoc
#   docker run --rm kcoap/libedhoc ctest --test-dir /build --output-on-failure
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

WORKDIR /build
CMD ["ctest", "--output-on-failure"]

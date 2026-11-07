FROM rust:1.93.1-slim-bookworm@sha256:5b9332190bb3b9ece73b810cd1f1e9f06343b294ce184bcb067f0747d7d333ea AS builder
WORKDIR /src
ENV CARGO_BUILD_JOBS=1
COPY Cargo.toml Cargo.lock ./
COPY src ./src
COPY vendor ./vendor
COPY .cargo ./.cargo
RUN cargo build --release --locked --offline --jobs 1

FROM debian:bookworm-slim@sha256:99c0b25c46d0214d5a2091092e7908907dcaecd6a962cde0ac700acee222b6f3
COPY --from=builder /src/target/release/pocket-relay /usr/local/bin/pocket-relay
COPY healthcheck.sh /usr/local/bin/pocket-healthcheck
RUN chmod 0555 /usr/local/bin/pocket-healthcheck
USER 65532:65532
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD ["/bin/bash", "/usr/local/bin/pocket-healthcheck"]
ENTRYPOINT ["/usr/local/bin/pocket-relay"]
CMD ["/run/pocket/relay.json"]

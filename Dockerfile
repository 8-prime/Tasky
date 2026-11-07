# Builds a static tasky-server binary and ships it in a minimal distroless image.
FROM rust:1-alpine AS build
RUN apk add --no-cache musl-dev
WORKDIR /src
COPY server/ .
RUN cargo build --release --locked -p tasky-server \
 && mkdir -p /out/data \
 && cp target/release/tasky-server /out/

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/tasky-server /usr/local/bin/tasky-server
COPY --from=build --chown=65532:65532 /out/data /data
ENV TASKY_BIND=0.0.0.0:8080 \
    TASKY_DATABASE=/data/tasky.db \
    RUST_LOG=info
EXPOSE 8080
VOLUME /data
ENTRYPOINT ["/usr/local/bin/tasky-server"]

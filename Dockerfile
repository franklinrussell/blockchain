# ─── Stage 1: Build ──────────────────────────────────────────────────────────
# Uses a full JDK image to compile the Scala source and assemble a fat jar.
FROM eclipse-temurin:17-jdk-jammy AS builder

# Install sbt
RUN apt-get update -q && apt-get install -yq curl && \
    curl -fL "https://github.com/sbt/sbt/releases/download/v1.12.5/sbt-1.12.5.tgz" \
         | tar -xz -C /usr/local && \
    ln -s /usr/local/sbt/bin/sbt /usr/local/bin/sbt && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

WORKDIR /build

# Copy build definition first so that dependency downloads are cached in their
# own layer and only re-run when build.sbt or project/ changes (not source).
COPY build.sbt .
COPY project/  project/
RUN sbt update

# Copy source and assemble the fat jar
COPY . .
RUN sbt assembly

# ─── Stage 2: Runtime ────────────────────────────────────────────────────────
# Minimal JRE — no build tools, no sbt, no source.
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

COPY --from=builder /build/target/scala-3.8.2/summitcoin.jar summitcoin.jar

# /data is mounted as a Render persistent disk so blockchain.json survives restarts.
# Create it here so the container works even without a disk mount (e.g. local Docker).
RUN mkdir -p /data

# DATA_DIR tells the persistence layer where to write blockchain.json.
# PORT tells the HTTP server which port to bind (Render injects its own value at runtime).
ENV DATA_DIR=/data
ENV PORT=8080

EXPOSE 8080

CMD ["java", "-jar", "summitcoin.jar"]

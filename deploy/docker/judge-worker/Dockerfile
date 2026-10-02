FROM maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /workspace
COPY backend/pom.xml pom.xml
COPY backend/judge-contract/pom.xml judge-contract/pom.xml
COPY backend/server/pom.xml server/pom.xml
COPY backend/judge-worker/pom.xml judge-worker/pom.xml
COPY backend/judge-contract/src judge-contract/src
COPY backend/judge-worker/src judge-worker/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -f pom.xml -B --projects judge-worker --also-make verify

FROM eclipse-temurin:21-jre-noble

ARG TARGETARCH
ARG GO_JUDGE_VERSION=1.12.1

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        gcc-13 \
        g++-13 \
        python3.12 \
        util-linux \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 teaching \
    && useradd --uid 10001 --gid teaching --no-create-home --shell /usr/sbin/nologin teaching \
    && mkdir -p /opt/teaching /opt/go-judge /var/lib/teaching/testcases \
    && chown -R teaching:teaching /opt/teaching /var/lib/teaching

RUN set -eu; \
    case "${TARGETARCH}" in \
      amd64) \
        asset="go-judge_${GO_JUDGE_VERSION}_linux_amd64v2"; \
        checksum="7d6ef61a43920ec5fde94165c4fa6dcb8520745face0f097517f0b14b2ee94b9" ;; \
      arm64) \
        asset="go-judge_${GO_JUDGE_VERSION}_linux_arm64"; \
        checksum="e688d80a27a5737d89ca763820ea60f1aa1dd2b428a5716e9a5df89912f17425" ;; \
      *) echo "不支持的镜像架构: ${TARGETARCH}" >&2; exit 1 ;; \
    esac; \
    curl --fail --location --silent --show-error \
      "https://github.com/criyle/go-judge/releases/download/v${GO_JUDGE_VERSION}/${asset}" \
      --output /opt/go-judge/go-judge; \
    echo "${checksum}  /opt/go-judge/go-judge" | sha256sum --check --strict; \
    chmod 0755 /opt/go-judge/go-judge

COPY --from=builder /workspace/judge-worker/target/teaching-judge-worker.jar /opt/teaching/judge-worker.jar
COPY deploy/docker/judge-worker/entrypoint.sh /usr/local/bin/teaching-judge-entrypoint

RUN chmod 0755 /usr/local/bin/teaching-judge-entrypoint

EXPOSE 8081
ENTRYPOINT ["/usr/local/bin/teaching-judge-entrypoint"]

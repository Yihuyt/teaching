FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /workspace
COPY backend/pom.xml pom.xml
COPY backend/judge-contract/pom.xml judge-contract/pom.xml
COPY backend/server/pom.xml server/pom.xml
COPY backend/judge-worker/pom.xml judge-worker/pom.xml
COPY backend/judge-contract/src judge-contract/src
COPY backend/server/src server/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn --batch-mode --no-transfer-progress \
    --file pom.xml \
    --projects server \
    --also-make \
    clean verify

FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache su-exec \
    && addgroup -S teaching \
    && adduser -S -G teaching -h /app teaching

WORKDIR /app
COPY --from=build --chown=teaching:teaching \
    /workspace/server/target/teaching-backend.jar \
    /app/teaching-backend.jar
COPY --chmod=0755 deploy/docker/server/entrypoint.sh /usr/local/bin/teaching-backend-entrypoint

EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/teaching-backend-entrypoint"]

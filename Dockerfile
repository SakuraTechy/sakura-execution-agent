# 第一阶段构建可执行的 shaded JAR，避免依赖开发机 target 目录。
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -DskipTests package

FROM eclipse-temurin:17-jre

ENV AGENT_HOME=/app/sakura-execution-agent

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --create-home --home-dir "$AGENT_HOME" --shell /usr/sbin/nologin sakura \
    && mkdir -p "$AGENT_HOME"/drivers "$AGENT_HOME"/conf "$AGENT_HOME"/logs "$AGENT_HOME"/workspace "$AGENT_HOME"/data

COPY --from=build /build/target/sakura-execution-agent-0.1.0-SNAPSHOT.jar "$AGENT_HOME/sakura-execution-agent.jar"
COPY drivers/ "$AGENT_HOME/drivers/"
# 仅作为镜像内默认文件；生产部署应挂载经过带外核对的 known_hosts。
COPY conf/known_hosts.example "$AGENT_HOME/conf/known_hosts"

RUN chown -R sakura:sakura "$AGENT_HOME" \
    && chmod 0750 "$AGENT_HOME" "$AGENT_HOME"/drivers "$AGENT_HOME"/conf \
    && chmod 0640 "$AGENT_HOME/conf/known_hosts"

USER sakura
WORKDIR "$AGENT_HOME"

EXPOSE 19091

ENTRYPOINT ["java", "-Dsakura.agent.bind=127.0.0.1", "-Dsakura.agent.port=19091", "-Dsakura.agent.driver-dir=/app/sakura-execution-agent/drivers", "-Dsakura.agent.known-hosts=/app/sakura-execution-agent/conf/known_hosts", "-Dsakura.agent.log-file=/app/sakura-execution-agent/logs/agent.log", "-Dsakura.agent.workspace=/app/sakura-execution-agent/workspace", "-Dsakura.agent.ledger-file=/app/sakura-execution-agent/data/task-ledger.json", "-jar", "/app/sakura-execution-agent/sakura-execution-agent.jar"]

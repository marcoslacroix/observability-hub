# syntax=docker/dockerfile:1.7
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# 1. Copia o Agente Java do Pyroscope que está na sua máquina (ou mantenha o ADD se preferir)
ADD --checksum=sha256:ad738199a90734f7b8392bae1ab892027d411acb953ef1f00cd6e8e7c669ff8f \
    https://github.com/grafana/pyroscope-java/releases/download/v2.9.2/pyroscope.jar /app/pyroscope.jar

# 2. Copia o Agente do OpenTelemetry direto da pasta local do projeto
COPY opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar

# 3. Copia o JAR da aplicação gerado pelo build
COPY target/pyroscope-0.0.1-SNAPSHOT.jar /app/app.jar

# 4. Executa a aplicação ativando ambos os agentes
ENTRYPOINT ["java", "-javaagent:/app/opentelemetry-javaagent.jar", "-javaagent:/app/pyroscope.jar", "-jar", "/app/app.jar"]
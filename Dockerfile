# syntax=docker/dockerfile:1.7
# Exemplo de Dockerfile padrão para Spring Boot
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
# Agente Java do Pyroscope (versão fixa, validada por checksum)
ADD --checksum=sha256:ad738199a90734f7b8392bae1ab892027d411acb953ef1f00cd6e8e7c669ff8f \
    https://github.com/grafana/pyroscope-java/releases/download/v2.9.2/pyroscope.jar /app/pyroscope.jar
COPY target/pyroscope-0.0.1-SNAPSHOT.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]

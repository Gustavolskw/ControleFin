FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
# Roda os testes (inclui os de segurança): se falharem, a imagem não é gerada.
RUN mvn -q -B package

FROM eclipse-temurin:21-jre
WORKDIR /app
ENV TZ=America/Sao_Paulo
# Não roda como root dentro do container.
RUN useradd --system --uid 10001 app \
    && mkdir -p /data/attachments && chown app /data/attachments && chmod 700 /data/attachments
COPY --from=build /app/target/controlefin-backend-*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

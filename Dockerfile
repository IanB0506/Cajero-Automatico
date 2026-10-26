# =====================================================================
# Etapa 1 (build): compila el JAR con Maven + JDK 21
# =====================================================================
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Modo batch con el progreso de descargas visible. Si una descarga queda 60 s sin
# respuesta se reintenta (por defecto Maven espera hasta 30 min en silencio).
ENV MAVEN_ARGS="-B -Daether.connector.requestTimeout=60000 -Daether.connector.http.retryHandler.count=5"

# Primero solo el pom.xml: las dependencias quedan en caché mientras no cambie
COPY pom.xml .
RUN mvn dependency:go-offline

COPY src ./src
RUN mvn package -DskipTests

# =====================================================================
# Etapa opcional (test): ejecuta la suite completa sobre H2 en memoria
#   docker compose --profile tests run --rm tests
# =====================================================================
FROM build AS test
# Corre una prueba unitaria para dejar en la imagen los plugins de JUnit/Surefire
RUN mvn test -Dtest=IsoParserUnitTest
CMD ["mvn", "test"]

# =====================================================================
# Etapa final (runtime): solo JRE 21 + el JAR, con usuario sin privilegios
# =====================================================================
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

RUN groupadd --system recolector && useradd --system --gid recolector --no-create-home recolector

COPY --from=build /workspace/target/*.jar /app/app.jar

USER recolector

# 8087 = Web / API REST / Actuator (cambiable con SERVER_PORT), 15000 = Socket TCP ISO 8583
EXPOSE 8087 15000

ENV SERVER_PORT=8087
ENV JAVA_OPTS="-XX:+UseZGC -XX:+ZGenerational -XX:MaxRAMPercentage=75"

HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=5 \
  CMD curl -fsS "http://localhost:${SERVER_PORT}/actuator/health" > /dev/null || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

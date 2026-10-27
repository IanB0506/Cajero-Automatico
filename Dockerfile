# =====================================================================
# Imagen NATIVA (por defecto): GraalVM Native Image 25 -> Distroless
#
#   docker compose up -d --build
#
# La compilación nativa tarda 5-10 minutos y necesita ~6 GB de RAM para
# Docker. Para una compilación rápida en JVM (Temurin 25 + ZGC) usa
# APP_DOCKERFILE=Dockerfile.jvm en el archivo .env.
# =====================================================================

# ---------------------------------------------------------------------
# Etapa 1 (deps): GraalVM 25 + Maven; dependencias en caché
# ---------------------------------------------------------------------
FROM ghcr.io/graalvm/native-image-community:25 AS deps

# Maven 3.9 tomado de la imagen oficial (la imagen de GraalVM no lo incluye)
COPY --from=maven:3.9-eclipse-temurin-25 /usr/share/maven /usr/share/maven
ENV MAVEN_HOME=/usr/share/maven
ENV PATH=/usr/share/maven/bin:$PATH

# Modo batch con el progreso de descargas visible y reintentos ante cortes de red
ENV MAVEN_ARGS="-B -Daether.connector.requestTimeout=60000 -Daether.connector.http.retryHandler.count=5"

WORKDIR /workspace
# Primero solo el pom.xml: las dependencias quedan en caché mientras no cambie
COPY pom.xml .
RUN mvn dependency:go-offline -Pnative
COPY src ./src

# ---------------------------------------------------------------------
# Etapa opcional (test): suite completa + cobertura sobre H2 en memoria
#   docker compose --profile tests run --rm tests
# ---------------------------------------------------------------------
FROM deps AS test
# Corre una prueba para dejar en la imagen los plugins de JUnit/Surefire/JaCoCo
RUN mvn test -Dtest=IsoParserUnitTest
CMD ["mvn", "verify"]

# ---------------------------------------------------------------------
# Etapa 2 (build): AOT de Spring + binario nativo (perfil "native" del pom)
# ---------------------------------------------------------------------
FROM deps AS build
RUN mvn -Pnative -DskipTests package

# ---------------------------------------------------------------------
# Etapa final (runtime): Distroless sin shell, usuario no root (65532)
# ---------------------------------------------------------------------
FROM gcr.io/distroless/cc-debian12:nonroot AS runtime
WORKDIR /app
COPY --from=build /workspace/target/recolector /app/recolector

# 8087 = Web / API REST / Actuator (cambiable con SERVER_PORT), 15000 = Socket TCP ISO 8583
EXPOSE 8087 15000
ENV SERVER_PORT=8087

# Sin curl ni shell: el propio binario consulta /actuator/health y devuelve 0 (UP) o 1
HEALTHCHECK --interval=10s --timeout=5s --start-period=20s --retries=5 \
  CMD ["/app/recolector", "--healthcheck"]

ENTRYPOINT ["/app/recolector"]

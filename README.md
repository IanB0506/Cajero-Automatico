# Recolector Transaccional ISO 8583 de Alto Rendimiento

Switch transaccional que recibe tramas **ISO 8583** por **TCP**, las traduce a un **Core Bancario REST** (SQL Server) y devuelve la respuesta ISO con el código de acción en el **DE 39**. Incluye un cajero automático web para la demostración, binario nativo **GraalVM 25** sobre **Distroless**, pruebas automatizadas con cobertura **> 80 %** y un plan de carga **JMeter**.

| Componente | Versión |
|---|---|
| Java | 25 (Virtual Threads, Records, Pattern Matching) |
| Spring Boot / Framework | 4.1.2 / 7.0 (RestClient, `RetryTemplate` del core) |
| Spring Integration IP | 7.1 (servidor TCP con prefijo de longitud de 2 bytes) |
| Jakarta EE | 11 (`jakarta.inject`, `jakarta.annotation`, `jakarta.validation`, `jakarta.persistence`) |
| Motor ISO 8583 | j8583 1.17 (`j8583.xml`) |
| Base de datos | SQL Server 2022 (H2 en modo MSSQL solo para pruebas) |
| Imagen nativa | GraalVM Native Image 25 → `gcr.io/distroless/cc-debian12:nonroot` |
| GC en JVM | Generational ZGC |
| Pruebas | JUnit 6, AssertJ, Mockito, MockMvc, WireMock 3, JaCoCo, JFR |
| Carga | Apache JMeter 5.6.3 |

La documentación técnica (decisiones de diseño, formato de las tramas, metodología y resultados de las mediciones) está en **[DOCUMENTACION.md](DOCUMENTACION.md)**.

---

## Contenido

1. [Inicio rápido con Docker (Windows)](#1-inicio-rápido-con-docker-windows)
2. [Uso: cajero web, API, Postman y socket TCP](#2-uso-cajero-web-api-postman-y-socket-tcp)
3. [Arquitectura](#3-arquitectura)
4. [Cumplimiento de la guía del proyecto](#4-cumplimiento-de-la-guía-del-proyecto)
5. [Pruebas y cobertura](#5-pruebas-y-cobertura)
6. [Prueba de carga con JMeter](#6-prueba-de-carga-con-jmeter)
7. [Resultados medidos](#7-resultados-medidos)
8. [Ejecución sin Docker (desarrollo)](#8-ejecución-sin-docker-desarrollo)
9. [Guía para la defensa](#9-guía-para-la-defensa)
10. [Estructura del repositorio](#10-estructura-del-repositorio)

---

## 1. Inicio rápido con Docker (Windows)

Solo se necesita **Docker Desktop** (backend WSL 2, contenedores Linux). No hace falta instalar Java, Maven ni SQL Server.

```powershell
git clone https://github.com/IanB0506/Cajero-Automatico.git
cd Cajero-Automatico
docker compose up -d --build
```

La primera vez tarda unos **10-15 minutos**: descarga SQL Server, las dependencias de Maven y compila el **binario nativo** (la compilación nativa sola tarda 5-10 minutos y usa mucha memoria; asigna al menos **8 GB** a Docker Desktop / WSL 2). Las siguientes veces solo se recompila lo que cambió.

> **¿Poca memoria o prisa?** En `.env` cambia `APP_DOCKERFILE=Dockerfile` por `APP_DOCKERFILE=Dockerfile.jvm` y vuelve a ejecutar `docker compose up -d --build`. Se construye la versión JVM (Temurin 25 + ZGC) en ~2 minutos. Todo lo demás funciona igual.

`docker compose` levanta, en orden:

| Contenedor | Función | Puerto en Windows |
|---|---|---|
| `cajero-sqlserver` | SQL Server 2022 (datos en el volumen `sqlserver-data`) | `localhost,14333` |
| `cajero-db-init` | Crea `CoreBancario5B` y ejecuta `init_sqlserver.sql` (idempotente); termina con `Exited (0)` | — |
| `cajero-app` | Recolector ISO 8583 + Core Bancario + cajero web (binario nativo en Distroless) | `8087` (HTTP) y `15000` (TCP) |

Cuando `docker compose ps` muestre `cajero-app` como **healthy**, abre **http://localhost:8087**.

| Acción | Comando |
|---|---|
| Ver los logs (incluye el tiempo de arranque) | `docker compose logs -f app` |
| Detener (conserva los datos) | `docker compose down` |
| Detener y borrar la BD | `docker compose down -v` |
| Recompilar después de cambiar el código | `docker compose up -d --build` |
| Pruebas automatizadas + cobertura | `docker compose --profile tests run --rm tests` |
| Prueba de carga JMeter | `docker compose --profile carga run --rm jmeter` |

Contraseñas, credenciales, puertos y zona horaria están en **`.env`**:

| Variable | Valor por defecto | Uso |
|---|---|---|
| `APP_DOCKERFILE` | `Dockerfile` | `Dockerfile` (nativo) o `Dockerfile.jvm` |
| `ADMIN_USER` / `ADMIN_PASSWORD` | `admin` / `Nexus2026_Admin` | Login de administración (web y API, HTTP Basic) |
| `CORE_API_KEY` | `nexus-core-api-key-desarrollo` | Cabecera `X-API-Key` del Recolector hacia el Core |
| `MSSQL_SA_PASSWORD` | `Nexus2026_Pass` | Usuario `sa` de SQL Server (fijada al crear el volumen) |
| `APP_HTTP_PORT` / `APP_TCP_PORT` / `SQLSERVER_PORT` | `8087` / `15000` / `14333` | Puertos publicados en Windows |
| `CARGA_TERMINALES` / `CARGA_DURACION` | `200` / `60` | Prueba de carga JMeter |

Si cambias `MSSQL_SA_PASSWORD` después del primer arranque, ejecuta `docker compose down -v` para recrear la BD.

---

## 2. Uso: cajero web, API, Postman y socket TCP

### Cuentas de demostración

| Cuenta | PAN | Titular | Saldo | Límite diario | Estado | PIN |
|---|---|---|---|---|---|---|
| 1001-2002-3001 | 4532000000000001 | Carlos Pérez | Q1,500.00 | Q500.00 | ACTIVE | 1234 |
| 1001-2002-3002 | 5412000000000002 | María Gómez | Q350.00 | Q300.00 | ACTIVE | 4321 |
| 1001-2002-3003 | 4000000000000003 | Juan López | Q500.00 | Q200.00 | EXPIRED | 2222 |
| 1001-2002-3004 | 4000000000000004 | Ana Morales | Q1,000.00 | Q500.00 | BLOCKED | 9999 |
| 1001-2002-3005 | 4000000000000005 | Pedro Ramos | Q10.00 | Q100.00 | ACTIVE | 1111 |

El PIN se guarda solo como hash SHA-256; la interfaz y la API nunca lo muestran. **Administración → Restaurar** (o `POST /api/core/reset`) devuelve estas cuentas a sus valores iniciales y limpia el historial.

### Cajero web (http://localhost:8087)

1. Clic en la ranura de tarjeta → elegir una cuenta.
2. Teclear el PIN (teclado en pantalla o físico). El PIN viaja dentro de la trama como **PIN block ISO 9564 formato 0 (DE 52)**.
3. Operaciones: retiro (0200), consulta de saldo (0100), **reversa del último retiro** (0420 con el RRN original), echo test (0800) y estado de cuenta.
4. Un PIN incorrecto responde **DE 39 = 55** y vuelve a pedirlo; al tercer intento la tarjeta se bloquea (**DE 39 = 75**).
5. **Acceso Administrador** (usuario/contraseña de `.env`, validados por el servidor): alta, edición (incluido cambio de PIN), recargas, bloqueo, borrado y restauración de cuentas; historial de transacciones; consola para inyectar tramas.

### API REST

| Método y ruta | Acceso | Descripción |
|---|---|---|
| `POST /api/iso/simular` | público | Arma una trama ISO 8583, la procesa como si llegara por TCP y devuelve tramas, bitmap y DE 39 |
| `GET /api/iso/metricas`, `POST /api/iso/rafaga?cantidad=50` | público | Métricas del switch y ráfaga concurrente en Virtual Threads |
| `POST /api/core/transacciones` | `X-API-Key` (Recolector) o ADMIN | Contrato Recolector → Core; responde el DE 39 en `codigoRespuesta` |
| `GET /api/core/cuentas[/{id}]`, `GET /api/core/cuentas/{id}/transacciones`, `GET /api/core/transacciones` | público | Consultas (sin PIN) |
| `POST/PUT/DELETE /api/core/cuentas...`, `.../recargar`, `.../toggle-bloqueo`, `POST /api/core/reset` | ADMIN (HTTP Basic) | Administración |
| `GET /actuator/health` | público | Incluye el estado del socket TCP (`tcpIso`) y de la BD |
| `GET /actuator/metrics/**` | ADMIN | Métricas (pausas de GC en modo JVM) |

### Postman

Importa `postman_collection.json` (variables `baseUrl`, `adminUser`, `adminPassword` y `apiKey` ya configuradas) y ejecútala completa con el **Collection Runner**: 30 peticiones con pruebas automáticas (retiro con PIN, PIN incorrecto, consulta, reversa del retiro, transferencia, rechazos 51/05/54/14, retransmisión idempotente, administración y seguridad 401).

### Socket TCP ISO 8583 (`localhost:15000`)

Cada trama va precedida por **2 bytes con su longitud** (big-endian, sin signo); la respuesta usa el mismo enmarcado sobre la misma conexión, que puede quedar abierta (keep-alive). Formato ASCII con bitmap hexadecimal y cabecera opcional `ISO015000050`. Detalle de los campos en [DOCUMENTACION.md](DOCUMENTACION.md#3-formato-de-las-tramas).

---

## 3. Arquitectura

```mermaid
flowchart LR
    T["Terminales ATM/POS<br/>(JMeter, cajero web, pruebas)"] -- "TCP :15000<br/>prefijo 2 bytes" --> GW
    subgraph APP["Recolector (Spring Boot 4.1 · Java 25 · nativo GraalVM)"]
        GW["Spring Integration IP<br/>TcpNetServerConnectionFactory<br/>1 Virtual Thread por conexión"] --> REC["RecolectorTransaccionalService<br/>j8583: parseo DE 3/4/11/37/102/52"]
        REC --> IDEM["IdempotenciaService<br/>caché RRN+STAN"]
        REC --> CLI["CoreBancarioClient<br/>RestClient + RetryTemplate<br/>X-API-Key"]
        WEB["Cajero web / API /api/iso"] --> REC
    end
    CLI -- "HTTP JSON :8087/api/core" --> CORE["Core Bancario<br/>CoreBancarioService<br/>bloqueo pesimista + UNIQUE(RRN,STAN)"]
    CORE --> DB[("SQL Server 2022<br/>CoreBancario5B")]
```

Flujo de un retiro (0200):

1. La terminal envía la trama con su longitud; Spring Integration la entrega en un **Virtual Thread**.
2. `RecolectorTransaccionalService` parsea con **j8583** y extrae DE 3 (tipo de operación), DE 4 (monto), DE 11 (STAN), DE 37 (RRN), DE 102 (cuenta) y DE 52 (PIN block). Una trama ilegible responde **DE 39 = 30**.
3. Si el par **RRN + STAN** ya fue respondido (retransmisión), devuelve la misma respuesta sin volver a debitar.
4. Arma un DTO inmutable (`record`) y lo envía al Core con **RestClient**; ante caída o HTTP 5xx reintenta con el **`RetryTemplate`** de Spring Framework 7 (seguro porque el Core es idempotente).
5. El Core bloquea la fila de la cuenta (`UPDLOCK`), valida estado, PIN, saldo y límite diario, debita y registra la transacción en la misma transacción de BD. La restricción `UNIQUE(rrn, stan)` impide aplicar dos veces la misma transacción aunque lleguen duplicados simultáneos.
6. El Recolector traduce la respuesta HTTP/JSON a la trama **0210** con DE 38 (autorización), **DE 39** y DE 54 (saldos).

---

## 4. Cumplimiento de la guía del proyecto

| Requisito | Implementación | Dónde |
|---|---|---|
| Spring Boot 4.1, Java 21+ (25), Jakarta EE 11 | Boot 4.1.2, Java 25, solo `jakarta.*` | `pom.xml` |
| Servidor TCP con Spring Integration IP y Virtual Threads | `TcpNetServerConnectionFactory` + `Tcp.inboundGateway`, ejecutor de Virtual Threads, `spring.threads.virtual.enabled=true` | `config/TcpIntegrationConfig.java`, `config/VirtualThreadsConfig.java` |
| j8583, MTI 0200/0210 (y 0100, 0420, 0800) | Guías de parseo y plantillas coherentes; respuestas siempre parseables | `j8583.xml`, `iso/IsoMessageService.java` |
| Extracción DE 3, 4, 11, 37, 102 | `IsoTransactionDto` (record) | `iso/IsoMessageService.java#extractTransaction` |
| Mapeo HTTP/JSON → DE 39 | 00, 05, 12, 13, 14, 25, 30, 51, 54, 55, 61, 68, 75, 91, 96 | `iso/IsoResponseCode.java`, `corebancario/CoreBancarioClient.java` |
| RestClient (Framework 7) con `jakarta.inject`/`jakarta.annotation` | `@Named` + `@Inject` en el cliente, `@PostConstruct` en el motor ISO; pool Apache HttpClient 5; `RetryTemplate` | `corebancario/CoreBancarioClient.java`, `iso/IsoMessageService.java` |
| Idempotencia por RRN/STAN | Caché en el Recolector + `UNIQUE(rrn, stan)` en el Core | `service/IdempotenciaService.java`, `CoreBancarioService.java` |
| Dockerfile multi-etapa GraalVM 25 → Distroless | `ghcr.io/graalvm/native-image-community:25` → `gcr.io/distroless/cc-debian12:nonroot`, HEALTHCHECK con el propio binario | `Dockerfile` |
| `RuntimeHintsRegistrar` | Reflexión de j8583, `j8583.xml` y su DTD | `config/IsoAotRuntimeHints.java`, `META-INF/spring/aot.factories` |
| JUnit 5+ y WireMock | 119 pruebas (unitarias, WireMock, MockMvc, TCP extremo a extremo, carga con JFR) | `src/test/java` |
| Script de carga | Plan JMeter (TCP con PIN block) + imagen Docker | `src/test/resources/load-test-iso8583.jmx`, `Dockerfile.jmeter` |
| Cobertura > 80 % | JaCoCo: el build falla por debajo del 80 % de líneas | `pom.xml` (`jacoco:check`) |
| Pausas ZGC ≤ 1 ms y sin pinning | Validado con JFR en la prueba de carga | `LoadTestSimulationTest.java` |
| Seguridad | HTTP Basic (admin), API Key (servicio), PIN con hash SHA-256 y PIN block | `config/SeguridadConfig.java` |

---

## 5. Pruebas y cobertura

```powershell
docker compose --profile tests run --rm tests
```

Ejecuta `mvn verify` sobre H2 en memoria (modo MSSQL) en un contenedor y deja el reporte de cobertura en **`reportes/jacoco/index.html`**. Con JDK 25 y Maven instalados también se puede correr `mvn verify` directamente.

| Clase de prueba | Qué valida |
|---|---|
| `IsoParserUnitTest` | Parseo/codificación j8583, bitmap, DE 54, tramas malformadas → DE 39 = 30 |
| `PinBlockTest`, `IsoResponseCodeTest`, `TransaccionCoreRequestTest` | PIN block ISO 9564, catálogo DE 39, tipo de operación por DE 3 |
| `WireMockCoreBancarioIntegrationTest` | RestClient contra un Core simulado: 4xx/5xx → DE 39, reintentos, timeouts (68), caída (91), API Key |
| `RecolectorTransaccionalServiceTest`, `IdempotenciaServiceTest` | Orquestación, retransmisiones, errores transitorios no cacheados |
| `CoreBancarioServiceIntegrationTest` | Saldos, límites, PIN y bloqueo por intentos, reversas, transferencias, concurrencia sin pérdidas de actualización |
| `CoreBancarioControllerIntegrationTest`, `IsoSimuladorControllerIntegrationTest` | API REST, seguridad (401), validaciones, reset |
| `RecolectorEndToEndTest` | Socket TCP real → Recolector → Core HTTP → BD, enmarcado de 2 bytes, keep-alive |
| `LoadTestSimulationTest` | Carga concurrente por TCP con JFR: pausas de ZGC ≤ 1 ms y ningún pinning de Virtual Threads |
| `IsoAotRuntimeHintsTest` | Registro de `RuntimeHints` para la imagen nativa |

---

## 6. Prueba de carga con JMeter

```powershell
docker compose up -d                                   # la app debe estar arriba
docker compose --profile carga run --rm jmeter         # 200 terminales durante 60 s
```

El reporte HTML queda en **`reportes/jmeter/html/index.html`**; el TPS es la columna **Throughput**. Terminales y duración se ajustan en `.env` (`CARGA_TERMINALES`, `CARGA_DURACION`).

Qué hace el plan (`src/test/resources/load-test-iso8583.jmx`):

- **setUp:** crea por HTTP (con credenciales de administrador) una cuenta por terminal: `LOAD-0001`… con saldo Q1,000,000 y PIN 1234 (si ya existen las reutiliza).
- **Carga:** cada hilo es una terminal con una **conexión TCP persistente** que envía retiros 0200 con **PIN block en el DE 52**, usando `LengthPrefixedBinaryTCPClientImpl` (prefijo de 2 bytes).
- **Aserción:** la respuesta debe ser **0210 con DE 39 = 00**; el DE 39 se guarda como código de respuesta de cada muestra.

Para ejecutarlo con un JMeter instalado en Windows, usa **Java 17 o 21** para JMeter (su Groovy no soporta Java 25):

```powershell
jmeter -n -t src\test\resources\load-test-iso8583.jmx -Jterminales=200 -Jduracion=60 -l resultados.jtl -e -o reporte-carga
```

Parámetros (`-J`): `host`, `tcpPort`, `httpPort`, `adminUser`, `adminPassword`, `terminales`, `rampa`, `duracion`, `procCode`, `montoCentavos`, `pin`.

---

## 7. Resultados medidos

Medidos en el entorno de desarrollo de esta entrega: **4 vCPU compartidas**, donde la aplicación, SQL Server y JMeter corren en la **misma máquina**. La metodología completa está en [DOCUMENTACION.md](DOCUMENTACION.md#6-mediciones-y-metodología).

| Métrica | Binario nativo (GraalVM 25) | JVM (JDK 25 + Generational ZGC) | Meta de la guía |
|---|---|---|---|
| Tiempo de arranque (`Started ... in`) | **0,50 - 0,73 s** | 8,9 s (21 s en frío) | < 100 ms |
| RSS tras arrancar | **251 MB** (114 MiB en `docker stats`) | 930 MB | — |
| RSS máximo bajo carga | 708 MB (492 MB con `-Xmx256m`) | 1.361 MB (`-Xmx1g`) | — |
| TPS sostenido (JMeter, 100 terminales, 60 s, retiros con PIN) | **518,6** | 516,4 | 2.000+ |
| Errores | **0 %** (31.089 transacciones) | 0 % (30.993) | — |
| Con `docker compose --profile carga` (200 terminales, contenedor nativo) | 514,0 TPS, 0 errores (30.957 transacciones) | — | — |
| Latencia p95 / p99 | 335 / 459 ms | 309 / 419 ms | — |
| Pausas de GC | Serial GC (CE) | **ZGC: máx. 0,90 ms, p99 0,19 ms, media 0,04 ms** (120 pausas) | ≤ 1 ms |
| Pinning de Virtual Threads | — | **0 eventos** atribuibles al código (JFR) | 0 |
| Cobertura (JaCoCo, 119 pruebas) | — | **96,7 % líneas · 84,5 % ramas** | > 80 % |

**Lectura honesta de los resultados:**

- **TPS:** con 4 vCPU compartidas entre la app, SQL Server y el generador de carga, el sistema satura la CPU en ~520 TPS; el cuello de botella es la CPU (0 errores y latencias estables durante toda la prueba). Cada transacción incluye parseo ISO, una llamada HTTP al Core, una transacción SQL con bloqueo de fila e inserción, y la respuesta. **No se alcanzaron 2.000 TPS en este equipo**; para medirlo en tu hardware ejecuta el plan JMeter (sección 6), idealmente con el generador de carga en otra máquina.
- **Arranque:** el binario nativo arranca **15-40 veces más rápido** que la JVM, pero en este equipo no baja de 100 ms: el contexto crea ~480 beans (JPA/Hibernate, Spring Security, Spring Integration, Actuator, Tomcat), cada uno ~1 ms. La meta de < 100 ms es alcanzable en servicios más pequeños o en CPU dedicadas más rápidas.
- **GC en el binario nativo:** GraalVM Community solo ofrece Serial GC en imágenes nativas (G1 es de Oracle GraalVM). Las pausas ≤ 1 ms se cumplen y validan en **modo JVM con Generational ZGC**.

---

## 8. Ejecución sin Docker (desarrollo)

Requisitos: **JDK 25** (Temurin o GraalVM), Maven 3.9 y SQL Server accesible.

1. Crear la BD y los datos: ejecutar `init_sqlserver.sql` sobre una BD `CoreBancario5B` (es idempotente; migra versiones anteriores).
2. Ajustar la conexión en `src/main/resources/application.yaml` o con variables de entorno: `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`.
3. Ejecutar:

```powershell
mvn spring-boot:run                     # JVM
mvn verify                              # pruebas + verificación de cobertura (H2, no requiere SQL Server)
mvn -Pnative -DskipTests package        # binario nativo (GraalVM 25; en Windows además Visual Studio Build Tools)
target\recolector.exe                   # (Windows) o ./target/recolector en Linux
```

---

## 9. Guía para la defensa

Demostración sugerida (15 minutos):

1. **Arquitectura (3 min):** diagrama de la sección 3; Virtual Threads por conexión TCP, Spring Integration IP, RestClient con `RetryTemplate`, idempotencia en dos niveles.
2. **Demo en vivo (4 min):**
   - `docker compose ps` → `cajero-app` *healthy* (HEALTHCHECK del propio binario: Distroless no tiene shell ni curl).
   - `docker compose logs app | findstr Started` → tiempo de arranque del binario nativo.
   - Cajero web: retiro con PIN correcto → 00; PIN incorrecto → 55; reversa del retiro → 0430 y saldo restaurado; visor de bitmap y trama.
   - Postman Runner: 30 peticiones en verde (incluye retransmisión idempotente y 401 sin credenciales).
3. **Nativo y Distroless (3 min):** `Dockerfile` multi-etapa; `IsoAotRuntimeHints`; `docker images` (tamaño); `docker exec cajero-app sh` falla porque no existe shell (superficie de ataque mínima); usuario `nonroot`.
4. **Pruebas y métricas (4 min):** `docker compose --profile tests run --rm tests` → reporte JaCoCo; `docker compose --profile carga run --rm jmeter` → reporte HTML; tabla de la sección 7 y explicación de la metodología y límites del equipo.
5. **Cierre (1 min).**

Preguntas probables y dónde está la respuesta: idempotencia ante duplicados simultáneos (`CoreBancarioService#procesar`), por qué no hay pinning (sin `synchronized` en rutas de E/S, `ReentrantLock`; `LoadTestSimulationTest`), mapeo HTTP → DE 39 (`CoreBancarioClient#codigoPorEstadoHttp`), formato del PIN block (`PinBlock`).

---

## 10. Estructura del repositorio

```
├── Dockerfile               # Nativo: GraalVM 25 -> Distroless (por defecto)
├── Dockerfile.jvm           # Alternativa JVM: Temurin 25 + ZGC
├── Dockerfile.jmeter        # JMeter 5.6.3 (Java 21) para la prueba de carga
├── docker-compose.yml       # sqlserver, db-init, app, tests (perfil), jmeter (perfil)
├── .env                     # Puertos, credenciales y selección de Dockerfile
├── init_sqlserver.sql       # Esquema, migración e índices de CoreBancario5B (idempotente)
├── postman_collection.json  # Colección con autenticación y pruebas automáticas
├── DOCUMENTACION.md         # Reporte técnico
└── src
    ├── main/java/com/banco/recolector
    │   ├── RecolectorApplication.java      # Arranque y modo --healthcheck
    │   ├── config/                         # TCP (Spring Integration), Virtual Threads, seguridad, AOT hints
    │   ├── iso/                            # j8583, PIN block, códigos DE 39, simulador REST
    │   ├── service/                        # Orquestación, idempotencia, health del socket
    │   └── corebancario/                   # RestClient, API REST, servicio, entidades y repositorios JPA
    ├── main/resources                      # application.yaml, j8583.xml, cajero web (static/index.html)
    └── test                                # 119 pruebas, perfil H2, plan JMeter
```

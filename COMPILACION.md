# Compilación y prueba — RED NEXUS

Instrucciones para **compilar** el Recolector Transaccional ISO 8583 (binario nativo GraalVM o JAR sobre la JVM), **levantarlo** y **probarlo**. Hay dos caminos:

| Camino | Qué se instala | Para quién |
|---|---|---|
| **A. Con Docker** (recomendado) | Solo Docker Desktop | Cualquier PC con Windows 10/11, Linux o macOS. Compila el binario nativo sin instalar GraalVM |
| **B. Sin Docker** | JDK 25, Maven 3.9 y SQL Server (GraalVM 25 para el nativo, solo en Linux) | Desarrollo y depuración en el IDE |

El detalle de cada prueba y de las demostraciones de la defensa está en [PRUEBAS.md](PRUEBAS.md).

## Contenido

- [1. Requisitos](#1-requisitos)
- [2. Obtener el código](#2-obtener-el-código)
- [3. Camino A: compilar y levantar con Docker](#3-camino-a-compilar-y-levantar-con-docker)
- [4. Verificar que la compilación funciona](#4-verificar-que-la-compilación-funciona)
- [5. Ejecutar las pruebas](#5-ejecutar-las-pruebas)
- [6. Camino B: compilar y probar sin Docker](#6-camino-b-compilar-y-probar-sin-docker)
- [7. Qué produce cada comando](#7-qué-produce-cada-comando)
- [8. Configuración](#8-configuración)
- [9. Problemas frecuentes de compilación](#9-problemas-frecuentes-de-compilación)

---

## 1. Requisitos

### Camino A (Docker)

| Requisito | Detalle |
|---|---|
| Docker Desktop | Windows 10/11 con backend **WSL 2** (contenedores Linux). En Linux, Docker Engine con el plugin `docker compose` |
| Memoria para Docker | **8 GB o más** para compilar el binario nativo (la versión JVM funciona con 4 GB). En Windows se ajusta en `.wslconfig` (sección 9) |
| Disco | **≈ 15 GB libres** (imágenes de SQL Server, GraalVM, Maven, JMeter y caché de compilación) |
| Internet | Solo la primera vez, para descargar imágenes y dependencias |

### Camino B (sin Docker)

| Requisito | Versión | Para qué |
|---|---|---|
| JDK | **25** (Temurin, Oracle o GraalVM) | Compilar y ejecutar |
| Maven | **3.9** o superior | Compilación, pruebas y empaquetado |
| SQL Server | 2019 o 2022 (o solo el contenedor del proyecto, ver 6.1) | Base de datos `CoreBancario5B` (las pruebas no la necesitan) |
| GraalVM | **25** con `native-image`, más `gcc` y `zlib` (solo **Linux**) | Opcional: binario nativo sin Docker |

---

## 2. Obtener el código

```cmd
git clone https://github.com/IanB0506/Cajero-Automatico.git
cd Cajero-Automatico
```

O descomprime el `.zip` del proyecto y abre una ventana de **CMD** dentro de la carpeta. Los comandos de este documento son para CMD; en **PowerShell** usa `curl.exe` en lugar de `curl` y `$env:NOMBRE="valor"` en lugar de `set NOMBRE=valor`.

---

## 3. Camino A: compilar y levantar con Docker

La versión de la app la elige **`APP_DOCKERFILE`** en el archivo `.env` (ábrelo con `notepad .env` y revisa el valor antes de compilar):

- `APP_DOCKERFILE=Dockerfile` → **binario nativo** GraalVM 25 sobre Distroless: la que pide el enunciado y la que se inspecciona en la defensa (sección 3.1).
- `APP_DOCKERFILE=Dockerfile.jvm` → **JVM**: compila en ≈ 2 minutos y con poca memoria (sección 3.2).

### 3.1 Compilación nativa (`APP_DOCKERFILE=Dockerfile`)

```cmd
docker compose up -d --build
```

Este único comando compila y levanta todo:

| Contenedor | Qué hace | Puertos en tu PC |
|---|---|---|
| `cajero-sqlserver` | SQL Server 2022 (datos en el volumen `sqlserver-data`) | `14333` |
| `cajero-db-init` | Crea la BD `CoreBancario5B` y ejecuta `init_sqlserver.sql`; termina con `Exited (0)` | — |
| `cajero-app` | Recolector ISO 8583 + Core Bancario + cajero web, **binario nativo** sobre Distroless | `8087` (HTTP) y `15000` (TCP) |

La imagen de la app se construye con el `Dockerfile` multi-etapa:

| Etapa | Imagen base | Qué hace |
|---|---|---|
| `deps` | `ghcr.io/graalvm/native-image-community:25` + Maven 3.9 | Descarga las dependencias (quedan en caché mientras no cambie el `pom.xml`) |
| `test` | `deps` | Solo para las pruebas (`docker compose --profile tests ...`) |
| `build` | `deps` | `mvn -Pnative -DskipTests package`: Spring AOT + GraalVM `native-image` → ejecutable `target/recolector` |
| `runtime` | `gcr.io/distroless/cc-debian12:nonroot` | Copia solo el binario; usuario sin privilegios 65532; sin shell |

**Tiempo:** la primera vez ≈ 10 – 15 minutos (descargas + compilación nativa de 5 – 10 minutos). Durante la compilación nativa el log muestra las etapas `[1/8] Initializing...` a `[8/8] Creating image...`; entre etapas puede pasar varios minutos sin imprimir nada mientras usa toda la CPU: no está congelada. Al final aparece `Finished generating 'recolector' in ...`. Las siguientes veces solo se recompila lo que cambió.

**Compilación terminada** cuando `docker compose ps` muestra `cajero-app` como **healthy**.

### 3.2 Compilación JVM (`APP_DOCKERFILE=Dockerfile.jvm`)

Compila en ≈ 2 minutos y necesita menos memoria. Con esta línea en `.env`

```
APP_DOCKERFILE=Dockerfile.jvm
```

`docker compose up -d --build` construye el JAR sobre **Temurin 25 con Generational ZGC** (`Dockerfile.jvm`); todo lo demás funciona igual. Para volver a la versión nativa, escribe `APP_DOCKERFILE=Dockerfile` y ejecuta otra vez `docker compose up -d --build`.

### 3.3 Las dos versiones a la vez (comparación nativo vs JVM)

```cmd
docker compose --profile comparacion up -d --build app-jvm
```

Levanta `cajero-app-jvm` (misma app sobre la JVM) en los puertos **8088** (HTTP) y **15001** (TCP), con la misma base de datos y con el registro de las pausas de ZGC activado. Se usa en la demostración B2 de [PRUEBAS.md](PRUEBAS.md#b2-binario-nativo-inspección-tiempo-de-arranque-y-ram).

### 3.4 Comandos de uso diario

| Acción | Comando |
|---|---|
| Ver el estado | `docker compose ps` |
| Ver los logs (incluye el tiempo de arranque) | `docker compose logs -f app` (salir con `Ctrl+C`) |
| Recompilar después de cambiar el código | `docker compose up -d --build` |
| Reiniciar solo la app | `docker compose restart app` |
| Detener (conserva los datos) | `docker compose down` |
| Detener y borrar la base de datos | `docker compose down -v` |
| Empezar desde cero | `docker compose down -v` y luego `docker compose up -d --build` |

---

## 4. Verificar que la compilación funciona

| # | Qué se verifica | Comando | Resultado esperado |
|---|---|---|---|
| 1 | Contenedores | `docker compose ps` | `cajero-app` y `cajero-sqlserver` **healthy**; `cajero-db-init` **Exited (0)** |
| 2 | Qué versión corre | `docker compose logs app \| findstr "AOT-processed"` | Versión nativa: `Starting AOT-processed RecolectorApplication using Java 25...`; versión JVM: no muestra nada |
| 3 | Arranque | `docker compose logs app \| findstr "Started"` | `Started RecolectorApplication in X seconds` |
| 4 | Salud de la BD y del socket TCP | `curl http://localhost:8087/actuator/health` | `"status":"UP"` con `db` y `tcpIso` en `UP` |
| 5 | Una transacción por el socket TCP | `docker compose --profile demo run --rm simulador retiro --monto 100` | `DE 39  Codigo de respuesta  00  APROBADA` |
| 6 | Interfaz web | Abrir http://localhost:8087 | Cajero automático; retiro con la cuenta 1001-2002-3001 y PIN 1234 → aprobado |
| 7 | Restaurar los datos de prueba | `curl -u admin:Nexus2026_Admin -X POST http://localhost:8087/api/core/reset` | `Cuentas restauradas al estado inicial.` |

La lista completa (14 pasos, incluye seguridad y Postman) está en el [README](README.md#verificar-que-todo-funciona-lista-de-comprobación).

---

## 5. Ejecutar las pruebas

| Prueba | Comando | Resultado esperado | Reporte |
|---|---|---|---|
| **Automatizadas** (JUnit 6, WireMock, MockMvc, TCP extremo a extremo, carga con JFR) + **cobertura ≥ 80 %** | `docker compose --profile tests run --rm --build tests` | `Tests run: 119, Failures: 0, Errors: 0, Skipped: 0`, `All coverage checks have been met.` y `BUILD SUCCESS` | `reportes\jacoco\index.html` y `reportes\reporte-carga-jfr.txt` |
| Una sola clase de prueba | `docker compose --profile tests run --rm tests mvn test -Dtest=RecolectorEndToEndTest` | `Tests run: 11, Failures: 0` y `BUILD SUCCESS` | — |
| **Carga** (JMeter, 200 terminales TCP, 60 s; la app debe estar arriba) | `docker compose --profile carga run --rm jmeter` | `Err: 0 (0.00%)` en la última línea `summary =` | `reportes\jmeter\html\index.html` |
| Funcionales (Postman) | Importar `postman_collection.json` → *Run collection* | 30 peticiones y 46 pruebas en verde | — |
| Simulador ISO 8583 por TCP | `docker compose --profile demo run --rm simulador retiro --monto 100` | Respuesta `0210` con DE 39 = 00 | — |

Notas:

- Las pruebas automatizadas **no necesitan** SQL Server ni la app levantada: usan H2 en memoria (modo SQL Server). La cobertura se verifica en el propio build: si baja del 80 % de líneas, `mvn verify` falla.
- Durante las pruebas aparecen algunas líneas `ERROR` y `WARN`: son esperadas, vienen de pruebas que envían tramas malformadas o simulan caídas del Core. Lo que cuenta es el resumen final.
- Para una carga más corta: `set CARGA_TERMINALES=50` y `set CARGA_DURACION=30` antes del comando de JMeter.

---

## 6. Camino B: compilar y probar sin Docker

### 6.1 Base de datos

Las **pruebas** no necesitan base de datos (usan H2). Para **ejecutar** la app hace falta SQL Server con la BD `CoreBancario5B`. Dos opciones:

**Opción 1: solo SQL Server en Docker** (lo más simple)

```cmd
docker compose up -d sqlserver db-init
```

Queda en `localhost:14333`, usuario `sa`, contraseña `Nexus2026_Pass` (la de `.env`), con la BD creada y los datos de demostración.

**Opción 2: un SQL Server propio**

```cmd
sqlcmd -S localhost -U sa -P TU_CONTRASEÑA -C -Q "IF DB_ID('CoreBancario5B') IS NULL CREATE DATABASE CoreBancario5B"
sqlcmd -S localhost -U sa -P TU_CONTRASEÑA -C -i init_sqlserver.sql
```

`init_sqlserver.sql` es **idempotente**: crea las tablas, índices y cuentas de demostración que falten sin borrar datos. También se puede abrir y ejecutar en SQL Server Management Studio.

### 6.2 Indicar la conexión a la base de datos

La conexión por defecto (`src/main/resources/application.yaml`) es `127.0.0.1:1434`, usuario `sa`, contraseña `admin`. Para usar otra, define estas variables en la misma ventana donde vas a ejecutar la app (ejemplo para la opción 1):

```cmd
set "SPRING_DATASOURCE_URL=jdbc:sqlserver://localhost:14333;databaseName=CoreBancario5B;encrypt=true;trustServerCertificate=true;sendStringParametersAsUnicode=false;"
set SPRING_DATASOURCE_USERNAME=sa
set SPRING_DATASOURCE_PASSWORD=Nexus2026_Pass
```

### 6.3 Compilar, probar y ejecutar con Maven (JVM)

| Acción | Comando |
|---|---|
| Compilar + 119 pruebas + verificación de cobertura ≥ 80 % | `mvn clean verify` |
| Una sola clase de prueba | `mvn test -Dtest=WireMockCoreBancarioIntegrationTest` |
| Prueba de carga con JFR más pesada | `mvn test -Dtest=LoadTestSimulationTest -Dcarga.terminales=200 -Dcarga.tx=100` |
| Empaquetar el JAR sin pruebas | `mvn clean package -DskipTests` |
| Ejecutar con Maven | `mvn spring-boot:run` |
| Ejecutar el JAR con ZGC | `java -XX:+UseZGC -jar target\recolector-transaccional-iso8583-1.0.0-SNAPSHOT.jar` |

- Reportes de `mvn verify`: cobertura en `target\site\jacoco\index.html` y reporte de carga con JFR en `target\site\reporte-carga-jfr.txt`.
- La app queda en http://localhost:8087 (HTTP) y en el puerto TCP **15000**. Si los contenedores del camino A están corriendo, detén la app de Docker antes (`docker compose stop app`) para liberar los puertos.
- El simulador se puede ejecutar directamente con el JDK 25: `java simulador\SimuladorIso.java retiro --monto 100` (opciones: `java simulador\SimuladorIso.java --ayuda`).

### 6.4 Binario nativo sin Docker (solo Linux)

```bash
# Requisitos (Debian/Ubuntu): GraalVM 25 como JAVA_HOME, más gcc y zlib
sudo apt install build-essential zlib1g-dev

mvn -Pnative -DskipTests package      # 5-10 minutos, necesita 6-8 GB de RAM libres
./target/recolector                   # usa las mismas variables SPRING_DATASOURCE_* del paso 6.2
```

El perfil `native` del `pom.xml` usa `--static-nolibc` (binario casi estático para Distroless), `-march=compatibility` (corre en cualquier CPU x86-64) y `-H:+AddAllCharsets`. Esas opciones son **solo para Linux**: en **Windows o macOS** el binario nativo se compila con Docker (`docker compose build app`), que además produce el ejecutable Linux que necesita la imagen Distroless.

---

## 7. Qué produce cada comando

| Comando | Resultado |
|---|---|
| `docker compose up -d --build` (o `docker compose build app`) con `APP_DOCKERFILE=Dockerfile` | Imagen `cajero-automatico-app`: binario nativo `/app/recolector` (≈ 226 MB) sobre Distroless; imagen total ≈ 340 MB |
| Igual con `APP_DOCKERFILE=Dockerfile.jvm` | Imagen `cajero-automatico-app` con el JAR sobre Temurin 25 JRE (≈ 600 MB) |
| `docker compose --profile comparacion build app-jvm` | Imagen `cajero-automatico-app-jvm` (JVM) para comparar |
| `docker compose --profile tests run --rm --build tests` | `reportes\jacoco\` (cobertura) y `reportes\reporte-carga-jfr.txt` (ZGC y pinning) |
| `docker compose --profile carga run --rm jmeter` | `reportes\jmeter\html\` (reporte HTML), `reportes\jmeter\resultados.jtl` y `reportes\jmeter\jmeter.log` |
| `mvn clean package -DskipTests` | `target\recolector-transaccional-iso8583-1.0.0-SNAPSHOT.jar` |
| `mvn clean verify` | El JAR + `target\site\jacoco\` + `target\site\reporte-carga-jfr.txt` |
| `mvn -Pnative -DskipTests package` (Linux + GraalVM 25) | `target/recolector` (ejecutable nativo de Linux) |

---

## 8. Configuración

En Docker todo se configura en el archivo **`.env`** (se carga solo). Sin Docker, con variables de entorno (las de la columna *Variable*) o en `application.yaml`.

| Variable | Valor en `.env` | Uso |
|---|---|---|
| `APP_DOCKERFILE` | `Dockerfile` o `Dockerfile.jvm` | Versión que se compila: nativa o JVM (sección 3) |
| `MSSQL_SA_PASSWORD` | `Nexus2026_Pass` | Contraseña de `sa` en SQL Server (se fija al crear el volumen; si la cambias, `docker compose down -v`) |
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | (los arma `docker-compose.yml`) | Conexión JDBC a SQL Server |
| `ADMIN_USER` / `ADMIN_PASSWORD` | `admin` / `Nexus2026_Admin` | Administrador de la web y de la API (HTTP Basic) |
| `CORE_API_KEY` | `nexus-core-api-key-desarrollo` | Cabecera `X-API-Key` del Recolector hacia el Core |
| `APP_HTTP_PORT` / `APP_TCP_PORT` | `8087` / `15000` | Puertos de la app nativa en tu PC |
| `SQLSERVER_PORT` | `14333` | Puerto de SQL Server en tu PC |
| `APP_JVM_HTTP_PORT` / `APP_JVM_TCP_PORT` | `8088` / `15001` | Puertos de `app-jvm` (perfil `comparacion`) |
| `CARGA_TERMINALES` / `CARGA_DURACION` / `CARGA_HOST` | `200` / `60` / `app` | Prueba de carga JMeter |
| `TZ` | `America/Guatemala` | Zona horaria de los contenedores |

Otras propiedades de `application.yaml` (se pueden cambiar con variables de entorno, por ejemplo `RECOLECTOR_TCP_PORT=16000`):

| Propiedad | Por defecto | Uso |
|---|---|---|
| `server.port` | `8087` | HTTP: web, API REST y Actuator |
| `recolector.tcp.port` | `15000` | Socket TCP ISO 8583 |
| `recolector.tcp.timeout-ms` | `30000` | Tiempo máximo de inactividad de una conexión TCP |
| `recolector.corebancario.read-timeout-ms` / `reintentos` | `5000` / `2` | Llamada del Recolector al Core (timeout → DE 39 = 68) |
| `recolector.idempotencia.ttl-minutes` | `60` | Cuánto tiempo recuerda el Recolector una transacción para detectar retransmisiones |
| `recolector.iso.moneda` | `320` | Moneda por defecto (Quetzal) |

---

## 9. Problemas frecuentes de compilación

| Problema | Solución |
|---|---|
| La compilación nativa tarda más de 20 minutos, la PC se pone muy lenta o falla con `OutOfMemoryError` / `exit code 137` | Docker tiene poca memoria. Revisa en el log la línea `Build resources: X GB of memory`: si es menor que 6 GB, sigue los pasos de abajo o usa `Dockerfile.jvm` (sección 3.2) |
| `[1/8] Initializing...` y luego nada durante minutos | Normal: está analizando el código. Espera a `[8/8] Creating image...` |
| `error during connect` o `docker daemon is not running` | Abre Docker Desktop y espera a que el indicador esté en verde |
| `port is already allocated` | Otro programa usa ese puerto: cámbialo en `.env` (`APP_HTTP_PORT`, `APP_TCP_PORT`, `SQLSERVER_PORT`) |
| `cajero-db-init` termina con error | `docker compose logs db-init`. Si cambiaste `MSSQL_SA_PASSWORD` después del primer arranque: `docker compose down -v` |
| Fallan descargas de Maven (`Could not transfer artifact`) | Problema de red: vuelve a ejecutar el comando; las descargas ya hechas quedan en caché |
| `mvn verify` falla con `Coverage checks have not been met` | La cobertura de líneas bajó del 80 %: agrega pruebas para el código nuevo |
| `mvn` dice `release version 25 not supported` | El `JAVA_HOME` apunta a un JDK anterior: instala el JDK 25 y verifica con `java -version` |

**Dar más memoria a Docker Desktop en Windows (WSL 2):**

1. Cancela con `Ctrl+C` y ejecuta `docker compose down`.
2. `notepad "%UserProfile%\.wslconfig"` y escribe (deja al menos 4 GB para Windows):
   ```
   [wsl2]
   memory=8GB
   ```
3. `wsl --shutdown`, vuelve a abrir Docker Desktop y ejecuta otra vez `docker compose up -d --build`.

Con el backend Hyper-V la memoria se ajusta en Docker Desktop → *Settings* → *Resources*.

# Guía de pruebas y demostraciones — RED NEXUS

Este documento explica, paso a paso, **cómo ejecutar las pruebas que pide el enunciado** y **cómo hacer las tres demostraciones de la defensa**:

1. Demostración en vivo de la ejecución transaccional (Simulador ISO → Recolector Spring Boot 4.1 → Core Bancario).
2. Inspección del binario nativo y demostración del tiempo de arranque y del consumo de RAM.
3. Explicación de las métricas obtenidas durante la prueba de carga.

Cómo compilar y levantar el sistema está en [COMPILACION.md](COMPILACION.md).

## Contenido

- [0. Antes de empezar](#0-antes-de-empezar)
- [Parte A. Pruebas que pide el enunciado](#parte-a-pruebas-que-pide-el-enunciado)
  - [A1. Pruebas automatizadas (JUnit, WireMock y cobertura > 80 %)](#a1-pruebas-automatizadas-junit-wiremock-y-cobertura--80-)
  - [A2. Prueba de carga con JMeter](#a2-prueba-de-carga-con-jmeter)
  - [A3. Métricas de rendimiento: cómo medir cada una](#a3-métricas-de-rendimiento-cómo-medir-cada-una)
  - [A4. Pruebas funcionales: Postman y cajero web](#a4-pruebas-funcionales-postman-y-cajero-web)
- [Parte B. Demostraciones de la defensa](#parte-b-demostraciones-de-la-defensa)
  - [B1. Ejecución transaccional en vivo](#b1-ejecución-transaccional-en-vivo-simulador-iso--recolector--core-bancario)
  - [B2. Binario nativo: inspección, arranque y RAM](#b2-binario-nativo-inspección-tiempo-de-arranque-y-ram)
  - [B3. Métricas de la prueba de carga](#b3-explicación-de-las-métricas-de-la-prueba-de-carga)
- [Parte C. Lista para el día de la defensa](#parte-c-lista-para-el-día-de-la-defensa)
- [Parte D. Problemas frecuentes](#parte-d-problemas-frecuentes)

---

## 0. Antes de empezar

1. El sistema debe estar levantado: `docker compose up -d --build` (ver [COMPILACION.md](COMPILACION.md)).
2. `docker compose ps` debe mostrar `cajero-app` y `cajero-sqlserver` como **healthy**.
3. Para la demostración del binario nativo (B2), `cajero-app` debe ser la **versión nativa**: en `.env`, `APP_DOCKERFILE=Dockerfile`. Compruébalo con `docker compose logs app | findstr "AOT-processed"`: si no muestra nada, la app corre sobre la JVM; cambia el valor y ejecuta `docker compose up -d --build` (ver [COMPILACION.md](COMPILACION.md#3-camino-a-compilar-y-levantar-con-docker)).
4. Todos los comandos son para el **Símbolo del sistema (CMD)** de Windows, dentro de la carpeta del proyecto. En **PowerShell** escribe `curl.exe` en lugar de `curl`; para las variables usa `$env:NOMBRE="valor"` en lugar de `set NOMBRE=valor`.
5. Para empezar con los datos limpios, **restaura los datos** (devuelve las cuentas a su saldo inicial, vacía el historial y la caché de idempotencia del Recolector, así que la demostración se puede repetir con los mismos RRN):

   ```cmd
   curl -u admin:Nexus2026_Admin -X POST http://localhost:8087/api/core/reset
   ```

   Respuesta: `Cuentas restauradas al estado inicial.` También se puede hacer desde la web: **Acceso Administrador → Restaurar datos**.

### Cuentas de demostración

| Cuenta | Titular | Saldo | Límite diario | Estado | PIN | Sirve para demostrar |
|---|---|---|---|---|---|---|
| 1001-2002-3001 | Carlos Pérez | Q1,500.00 | Q500.00 | ACTIVE | 1234 | Retiro aprobado, retransmisión, reversa, PIN incorrecto, límite diario |
| 1001-2002-3002 | María Gómez | Q350.00 | Q300.00 | ACTIVE | 4321 | Consulta de saldo |
| 1001-2002-3003 | Juan López | Q500.00 | Q200.00 | EXPIRED | 2222 | Tarjeta vencida (54) |
| 1001-2002-3004 | Ana Morales | Q1,000.00 | Q500.00 | BLOCKED | 9999 | Tarjeta bloqueada (05) |
| 1001-2002-3005 | Pedro Ramos | Q10.00 | Q100.00 | ACTIVE | 1111 | Fondos insuficientes (51) |

---

# Parte A. Pruebas que pide el enunciado

## A1. Pruebas automatizadas (JUnit, WireMock y cobertura > 80 %)

```cmd
docker compose --profile tests run --rm --build tests
```

- Construye la imagen de pruebas y ejecuta `mvn verify`: compila, corre las **119 pruebas** y verifica que la cobertura de líneas sea **≥ 80 %** (si es menor, el build falla).
- No necesita SQL Server ni la app levantada: usa una base **H2 en memoria en modo SQL Server** y levanta su propio servidor TCP.
- Tarda unos minutos (la primera vez más, porque construye la imagen).

**Resultado esperado** (al final de la salida):

```
[INFO] Tests run: 119, Failures: 0, Errors: 0, Skipped: 0
[INFO] All coverage checks have been met.
[INFO] BUILD SUCCESS
```

**Reportes que quedan en la carpeta `reportes`:**

| Archivo | Contenido | Cómo abrirlo |
|---|---|---|
| `reportes\jacoco\index.html` | Cobertura por paquete y clase. Fila **Total**: 97 % de instrucciones y 84 % de ramas; líneas: 1.013 de 1.047 cubiertas (**96,8 %**) | `start reportes\jacoco\index.html` |
| `reportes\reporte-carga-jfr.txt` | Resultado de la prueba de carga con Java Flight Recorder: TPS, latencias, **pausas de ZGC** y **pinning** (ver [B3](#b3-explicación-de-las-métricas-de-la-prueba-de-carga)) | `notepad reportes\reporte-carga-jfr.txt` |

**Qué valida cada grupo de pruebas** (relación con el enunciado):

| Requisito | Clase de prueba | Qué comprueba |
|---|---|---|
| Parseo ISO 8583 con j8583 | `IsoParserUnitTest`, `PinBlockTest`, `IsoResponseCodeTest` | Bitmap, DE 54, tramas malformadas → DE 39 = 30, PIN block ISO 9564, catálogo DE 39 |
| WireMock (Core simulado) | `WireMockCoreBancarioIntegrationTest` | RestClient: HTTP 4xx/5xx → DE 39, reintentos, timeout → 68, Core caído → 91, envío de la API Key |
| Idempotencia RRN/STAN | `IdempotenciaServiceTest`, `RecolectorTransaccionalServiceTest`, `CoreBancarioServiceIntegrationTest` | Retransmisiones sin doble débito, duplicados simultáneos, errores transitorios no cacheados |
| Lógica del Core | `CoreBancarioServiceIntegrationTest`, `CoreBancarioControllerIntegrationTest` | Saldos, límite diario, PIN y bloqueo por intentos, reversas, transferencias, seguridad (401) |
| Flujo completo por TCP | `RecolectorEndToEndTest`, `IsoSimuladorControllerIntegrationTest` | Socket real → Recolector → Core HTTP → BD, prefijo de 2 bytes, keep-alive |
| Virtual Threads, ZGC ≤ 1 ms, sin pinning | `LoadTestSimulationTest` | 100 terminales concurrentes por TCP con JFR; falla si una pausa de GC supera 1 ms o hay pinning |
| Imagen nativa | `IsoAotRuntimeHintsTest` | Registro de los `RuntimeHints` de j8583 |

Para correr una sola clase: `docker compose --profile tests run --rm tests mvn test -Dtest=RecolectorEndToEndTest` (o `mvn test -Dtest=...` si tienes Maven instalado).

## A2. Prueba de carga con JMeter

```cmd
docker compose --profile carga run --rm jmeter
```

- La app debe estar arriba (el servicio espera a que `cajero-app` esté *healthy*).
- Por defecto: **200 terminales** durante **60 s**. Cada terminal es una conexión TCP persistente que envía retiros 0200 con PIN block (DE 52); la respuesta debe ser 0210 con DE 39 = 00.
- Antes de la carga crea una cuenta `LOAD-xxxx` por terminal (saldo Q1,000,000, PIN 1234) y al terminar las borra. El historial que deja se limpia con **Restaurar datos**.
- Tarda unos 2 minutos (incluye generar el reporte).

**Resultado esperado en la consola** (la última línea `summary =`):

```
summary =  31009 in 00:01:40 =  308.7/s Avg:   351 Min:     6 Max: 21074 Err:     0 (0.00%)
```

Lo importante es **`Err: 0 (0.00%)`**. El valor `/s` y el `Max` de esa línea incluyen el alta y la baja de las cuentas de carga (peticiones HTTP lentas, una sola vez); el TPS de los retiros se lee en el reporte.

**Reporte HTML:** `start reportes\jmeter\html\index.html` (cómo leerlo en [B3](#b3-explicación-de-las-métricas-de-la-prueba-de-carga)).

**Variantes** (las variables valen solo para esa ventana de CMD):

```cmd
set CARGA_TERMINALES=50
set CARGA_DURACION=30
docker compose --profile carga run --rm jmeter
```

| Variable | Por defecto | Para qué |
|---|---|---|
| `CARGA_TERMINALES` | 200 | Terminales (conexiones TCP) concurrentes |
| `CARGA_DURACION` | 60 | Duración de la carga en segundos |
| `CARGA_HOST` | `app` | `app-jvm` dirige la carga a la versión JVM (ver [B2](#b2-binario-nativo-inspección-tiempo-de-arranque-y-ram)) |

## A3. Métricas de rendimiento: cómo medir cada una

Los resultados de referencia se midieron en el equipo de desarrollo: **4 vCPU compartidas** entre la app, SQL Server y JMeter. En otra PC los números cambian; lo que debe mantenerse es **0 % de errores**, pausas de ZGC **< 1 ms** y **0 pinning**.

| Métrica | Meta del enunciado | Cómo se mide | Resultado de referencia |
|---|---|---|---|
| Arranque del binario nativo | < 100 ms | `docker compose restart app` y luego `docker compose logs app \| findstr "Started"` (última línea) | **≈ 0,4 – 0,7 s** (JVM: ≈ 8 – 9 s) |
| Memoria RAM | — | `docker stats --no-stream cajero-app` o `curl http://localhost:8087/api/iso/metricas` (`memoriaRssMb`) | **≈ 114 MiB** en `docker stats`, RSS ≈ 235 – 260 MB (JVM: ≈ 930 MiB) |
| TPS sostenido | 2.000+ | JMeter: columna *Transactions/s* (grupo *Throughput*) de la fila **0200 Retiro ISO 8583** | **≈ 515 TPS** con 0 errores |
| Latencia | — | JMeter: columnas *95th / 99th pct* | p95 ≈ 335 ms y p99 ≈ 459 ms con 100 terminales (con 200: ≈ 781 / 1.143 ms) |
| Pausas de GC (ZGC) | ≤ 1 ms | `reportes\reporte-carga-jfr.txt` (*Pausa máxima de GC*) o, en vivo, `docker compose logs app-jvm \| findstr "Pause"` | **0,10 ms** de pausa máxima |
| Pinning de Virtual Threads | 0 | `reportes\reporte-carga-jfr.txt` (*Eventos de pinning*) | **0** en código de la aplicación |
| Cobertura | > 80 % | `reportes\jacoco\index.html` | **96,8 % de líneas**, 84,5 % de ramas |

Lectura honesta (para explicarla si preguntan):

- **2.000 TPS no se alcanzaron en este equipo**: con 4 vCPU compartidas la CPU se satura en ≈ 520 TPS (sin errores y con latencias estables). Cada transacción incluye parseo ISO, una llamada HTTP al Core, una transacción SQL con bloqueo de fila e inserción, y la respuesta ISO.
- **El arranque nativo no baja de 100 ms aquí**, pero es 15 – 20 veces más rápido que la JVM: el contexto crea ≈ 480 beans (JPA/Hibernate, Spring Security, Spring Integration, Actuator, Tomcat).
- **Las pausas ≤ 1 ms se miden en modo JVM con Generational ZGC**: GraalVM Community solo trae Serial GC en las imágenes nativas.

## A4. Pruebas funcionales: Postman y cajero web

- **Postman:** importar `postman_collection.json` → *Run collection*. Resultado: **30 peticiones y 46 pruebas en verde** (retiro con PIN, PIN incorrecto, consulta, reversa, transferencia, rechazos 51/05/54/14, retransmisión idempotente, administración y 401 sin credenciales). Restaura los datos antes de correrla.
- **Cajero web:** http://localhost:8087 → ranura de tarjeta → cuenta → PIN → retiro, consulta, reversa del último retiro, echo test.
- **Lista rápida de verificación** (14 pasos con comandos y resultados esperados): sección *Verificar que todo funciona* del [README](README.md#verificar-que-todo-funciona-lista-de-comprobación).

---

# Parte B. Demostraciones de la defensa

## B1. Ejecución transaccional en vivo (Simulador ISO → Recolector → Core Bancario)

### Qué se demuestra

```
 Simulador ISO 8583              Recolector (Spring Boot 4.1)                Core Bancario
 (terminal ATM/POS)              binario nativo, Virtual Threads              (API REST + SQL Server)
        |                                   |                                         |
        |-- trama 0200 por TCP :15000 ----->|  j8583 parsea DE 3, 4, 11, 37, 52, 102   |
        |   (2 bytes de longitud + trama)   |  idempotencia RRN + STAN                 |
        |                                   |-- HTTP JSON + X-API-Key --------------->|  valida PIN, saldo, límite
        |                                   |                                         |  debita y registra (BD)
        |                                   |<-- JSON con codigoRespuesta ------------|
        |<-- trama 0210 (DE 38, 39, 54) ----|                                         |
```

El **simulador** (`simulador/SimuladorIso.java`) hace lo mismo que un cajero real: arma la trama ISO 8583 (ASCII, bitmap hexadecimal, PIN block ISO 9564 formato 0 en el DE 52), la envía por **TCP al puerto 15000** con el prefijo de 2 bytes y muestra la respuesta campo por campo. Es un programa Java sin dependencias que corre en un contenedor con JDK 25.

### Guion de la demostración (≈ 4 minutos)

Primero, restaura los datos (sección 0). Todos los comandos empiezan con:

```cmd
docker compose --profile demo run --rm simulador
```

seguido de la operación. Por ejemplo, el paso 1 completo es `docker compose --profile demo run --rm simulador retiro --monto 100 --rrn 100000000001`.

| # | Qué se demuestra | Operación y opciones | Resultado esperado |
|---|---|---|---|
| 1 | **Retiro aprobado** | `retiro --monto 100 --rrn 100000000001` | `0210`, **DE 39 = 00 APROBADA**, DE 38 con la autorización, DE 54 saldo **Q1,400.00** |
| 2 | **Retransmisión (idempotencia)**: la misma trama dos veces | `retiro --monto 100 --rrn 100000000001 --repetir 2` | Las dos respuestas son iguales (**misma autorización**) y el saldo sigue en **Q1,400.00**: un solo débito |
| 3 | **Reversa** del retiro | `reversa --monto 100 --rrn 100000000001` | `0430`, DE 39 = 00, saldo **Q1,500.00** (dinero devuelto) |
| 4 | Reversa repetida | `reversa --monto 100 --rrn 100000000001` | `0430`, **DE 39 = 13**: ya no queda monto por reversar |
| 5 | **PIN incorrecto** | `retiro --pin 0000` | **DE 39 = 55 PIN INCORRECTO** |
| 6 | **Consulta de saldo** (otro MTI) | `consulta --cuenta 1001-2002-3002 --pin 4321` | `0110`, DE 39 = 00, DE 54 **Q350.00** |
| 7 | Fondos insuficientes | `retiro --cuenta 1001-2002-3005 --pin 1111 --monto 50` | **DE 39 = 51**, DE 54 Q10.00 |
| 8 | Límite diario | `retiro --monto 600` | **DE 39 = 61** (límite Q500) |
| 9 | Tarjeta bloqueada | `retiro --cuenta 1001-2002-3004 --pin 9999` | **DE 39 = 05** |
| 10 | Tarjeta vencida | `retiro --cuenta 1001-2002-3003 --pin 2222` | **DE 39 = 54** |
| 11 | **Echo test** de red | `eco` | `0810`, DE 39 = 00 |

Para una demo corta bastan los pasos **1, 2, 3, 5 y 6**. No repitas el PIN incorrecto 3 veces seguidas: al tercer intento la tarjeta se bloquea (**DE 39 = 75**) hasta restaurar los datos.

### Salida del paso 1 (real) y cómo explicarla

```
RED NEXUS - Simulador ISO 8583 por TCP
Destino: app:15000

>> 0200  RETIRO  Q100.00  cuenta 1001-2002-3001  tarjeta 4532 **** 0001
   Trama enviada (165 bytes, mas 2 bytes de longitud):
   ISO0150000500200F238000008809000000000000400000016453200000000000101000000000001000010310335470000010335471031100000000001SIMUL001320041214FFFFFFFFFF141001-2002-3001
   Campos enviados:
     DE 2   Tarjeta (PAN)        453200******0001
     DE 3   Codigo de proceso    010000  (retiro)
     DE 4   Monto                000000010000  (Q100.00)
     DE 7   Fecha y hora         1031033547
     DE 11  STAN                 000001
     DE 12  Hora local           033547
     DE 13  Fecha local          1031
     DE 37  RRN                  100000000001
     DE 41  Terminal             SIMUL001
     DE 49  Moneda               320  (quetzales)
     DE 52  PIN block            041214FFFFFFFFFF
     DE 102 Cuenta               1001-2002-3001

<< 0210 recibida en 21 ms (200 bytes):
   ISO0150000500210F23800000E808400000000000400000016...   (trama de respuesta recortada)
   DE 39  Codigo de respuesta  00  APROBADA
   DE 38  Autorizacion         100002
   DE 54  Saldos               contable Q1,400.00 - disponible Q1,400.00
   DE 37  RRN                  100000000001
```

Cómo se lee la trama enviada:

| Parte | Valor | Significado |
|---|---|---|
| Cabecera | `ISO015000050` | Cabecera ISO opcional de 12 caracteres |
| MTI | `0200` | Solicitud financiera (retiro). La respuesta es `0210` |
| Bitmap primario | `F238000008809000` | En hexadecimal: indica qué campos 1 – 64 vienen. El primer bit encendido avisa que hay bitmap secundario |
| Bitmap secundario | `0000000004000000` | Campos 65 – 128: solo el **DE 102** (cuenta) |
| Campos | `16` `4532000000000001` `010000` … | Cada DE en orden; los de longitud variable llevan su longitud delante (DE 2 = `16` + PAN, DE 102 = `14` + cuenta) |
| DE 52 | `041214FFFFFFFFFF` | PIN block: el PIN **no viaja en claro**, va combinado (XOR) con el PAN según ISO 9564 formato 0 |

### Mostrar el efecto en el Core Bancario y en los logs

```cmd
curl http://localhost:8087/api/core/cuentas/1001-2002-3001
curl http://localhost:8087/api/core/cuentas/1001-2002-3001/transacciones
docker compose logs --tail 30 app
```

- La cuenta muestra el `saldoDisponible` actualizado y el historial registra el retiro y la reversa con el **RRN 100000000001**.
- El historial muestra para cada transacción el RRN, STAN, tipo, `saldoAnterior`, `saldoPosterior`, el código de respuesta y la autorización.
- En los logs del paso 2 aparece `Transacción duplicada detectada (Idempotencia Activada): RRN=100000000001 STAN=000001`, en un hilo `iso-tcp-N`: los Virtual Threads que atienden el socket TCP.
- Visualmente: http://localhost:8087 → **Acceso Administrador** (`admin` / `Nexus2026_Admin`) → historial de transacciones.

Complemento visual: el **cajero web** (http://localhost:8087) hace las mismas operaciones con botones y la **Consola ISO 8583** del panel de administración muestra las tramas enviada y recibida. El simulador es la prueba más fiel porque entra por el **socket TCP** igual que un cajero real.

> Sin Docker, con JDK 25 instalado: `java simulador\SimuladorIso.java retiro --monto 100` (por defecto se conecta a `localhost:15000`). Ayuda completa: `java simulador\SimuladorIso.java --ayuda`.

## B2. Binario nativo: inspección, tiempo de arranque y RAM

Requiere que `cajero-app` sea la versión nativa (`APP_DOCKERFILE=Dockerfile`, sección 0).

### Paso 1. La imagen: tamaño, capas y usuario

```cmd
docker images cajero-automatico-app
docker history cajero-automatico-app
docker image inspect cajero-automatico-app --format "Usuario={{.Config.User}} Entrypoint={{.Config.Entrypoint}}"
```

| Qué mostrar | Resultado esperado | Qué explicar |
|---|---|---|
| Tamaño | ≈ 340 MB (la versión JVM ≈ 600 MB) | La imagen final solo tiene Distroless y el binario |
| Capas (`docker history`) | Una capa de **≈ 226 MB** `COPY /workspace/target/recolector /app/recolector` sobre capas `bazel build @bookworm//...` de Distroless | El binario es todo el programa (Spring, Tomcat, Hibernate, j8583 ya compilados a código máquina); Distroless aporta solo lo mínimo: libc, OpenSSL, certificados y zona horaria |
| Usuario y entrada | `Usuario=65532 Entrypoint=[/app/recolector]` | Corre como usuario sin privilegios (*nonroot*) y arranca directamente el ejecutable, sin `java -jar` |

### Paso 2. Dentro del contenedor no hay shell ni JVM

```cmd
docker exec cajero-app sh
docker exec cajero-app java -version
docker top cajero-app
```

| Comando | Resultado esperado |
|---|---|
| `docker exec cajero-app sh` | `OCI runtime exec failed: ... exec: "sh": executable file not found in $PATH` |
| `docker exec cajero-app java -version` | `... exec: "java": executable file not found in $PATH` |
| `docker top cajero-app` | Un solo proceso: `UID 65532` … `CMD /app/recolector` |

Explicación: **no hay JVM** (el código ya está compilado) y **no hay shell, ni curl, ni gestor de paquetes**: un atacante que lograra entrar no tendría herramientas. Por eso el HEALTHCHECK lo hace el propio binario (`/app/recolector --healthcheck`).

### Paso 3. El log confirma que es la versión AOT/nativa

```cmd
docker compose logs app | findstr "AOT-processed"
```

Resultado: `Starting AOT-processed RecolectorApplication using Java 25.0.2 with PID 1 (/app/recolector started by nonroot in /app)`. *AOT-processed* significa que Spring resolvió la configuración al compilar; *PID 1* y `/app/recolector` muestran que el proceso es el ejecutable nativo.

Opcional, para enseñar el archivo: `docker cp cajero-app:/app/recolector recolector-linux` y `dir recolector-linux` (≈ 226 MB, ejecutable de Linux x86-64; no se ejecuta en Windows). Bórralo después con `del recolector-linux`.

Qué hay dentro del binario (lo imprime `native-image` al final de la compilación, etapa `[8/8] Creating image`):

| Parte | Tamaño | Qué es |
|---|---|---|
| *code area* | 107 MB (147.486 unidades de compilación) | El código de la app, Spring, Hibernate, Tomcat, j8583 y el JDK ya compilado a código máquina |
| *image heap* | 109 MB (927.624 objetos) | Objetos creados durante la compilación (cadenas, clases, configuración) que se cargan listos, sin construirlos al arrancar |
| *other data* | 23 MB | Metadatos |
| **Total** | **238 MB de imagen, 226 MB en disco** | Las librerías con más código: Hibernate (20 MB), `java.base` (17 MB) y Tomcat (6 MB) |

### Paso 4. Tiempo de arranque

```cmd
docker compose restart app
docker compose logs app | findstr "Started"
```

La **última línea** es el arranque recién hecho, por ejemplo: `Started RecolectorApplication in 0.423 seconds (process running for 0.462)`. El primer arranque después de encender la PC tarda más (≈ 1 – 4 s, el disco aún no está en caché): mide siempre con `restart`.

### Paso 5. Consumo de RAM

```cmd
docker stats --no-stream cajero-app
curl http://localhost:8087/api/iso/metricas
```

- `docker stats`, columna **MEM USAGE**: ≈ **114 MiB**.
- `/api/iso/metricas`: `"modoEjecucion":"Binario nativo GraalVM Native Image"`, `"memoriaRssMb":234` (≈ 235 – 260) y `"recolectorBasura":"young generation scavenger, complete scavenger"` (Serial GC de la imagen nativa).
- La diferencia entre los dos números: el **RSS** también cuenta las páginas del archivo del binario cargadas en memoria; `docker stats` no las cuenta porque el sistema puede liberarlas y volver a leerlas del disco.

### Paso 6. Comparación con la misma aplicación sobre la JVM

```cmd
docker compose --profile comparacion up -d --build app-jvm
docker compose restart app-jvm
docker compose logs app-jvm | findstr "Started"
docker stats --no-stream cajero-app cajero-app-jvm
curl http://localhost:8088/api/iso/metricas
```

`app-jvm` es el mismo código empaquetado como JAR sobre Temurin 25 con ZGC; usa los puertos **8088** (HTTP) y **15001** (TCP) y la misma base de datos. La primera vez se compila en ≈ 2 – 3 minutos.

| Medición | Binario nativo (`cajero-app`) | JVM (`cajero-app-jvm`) | Diferencia |
|---|---|---|---|
| Arranque (`Started ... in`) | **≈ 0,4 – 0,7 s** | ≈ 8 – 9 s | ≈ 15 – 20 veces más rápido |
| RAM tras arrancar (`docker stats`) | **≈ 114 MiB** | ≈ 930 MiB | ≈ 8 veces menos |
| RSS (`/api/iso/metricas`) | ≈ 235 – 260 MB | ≈ 930 – 960 MB | ≈ 4 veces menos |
| Tamaño de la imagen | ≈ 340 MB | ≈ 600 MB | — |
| Recolector de basura | Serial GC | Generational ZGC | — |

Compara la RAM **justo después de arrancar**: bajo carga la JVM hace crecer su heap (puede usar hasta el 75 % de la memoria del contenedor) y la diferencia aumenta.

**Por qué el nativo arranca más rápido y usa menos RAM:** GraalVM compila todo a código máquina **antes** de ejecutar (AOT): no hay que cargar ni verificar clases, ni interpretar bytecode, ni esperar al compilador JIT. Spring AOT dejó resuelta la configuración en la compilación y solo entra al binario el código alcanzable (*mundo cerrado*). La contrapartida: la compilación tarda 5 – 10 minutos y necesita mucha RAM, y cualquier reflexión debe declararse (`IsoAotRuntimeHints` para j8583).

Al terminar puedes detener la versión JVM: `docker compose stop app-jvm`.

## B3. Explicación de las métricas de la prueba de carga

Ejecuta la carga **antes** de la presentación (A2) para tener el reporte listo. Si quieres mostrarla en vivo, usa una versión corta (`set CARGA_TERMINALES=50` y `set CARGA_DURACION=30`) y abre en otra ventana `docker stats`: se ve cuánta CPU usa cada contenedor (la app, SQL Server y JMeter) mientras corre. En nuestro equipo la CPU llegó al máximo: ese es el cuello de botella.

### 1. Reporte de JMeter (`reportes\jmeter\html\index.html`)

En la tabla **Statistics** usa la fila **0200 Retiro ISO 8583** (la fila *Total* mezcla el alta y la baja de cuentas por HTTP):

| Columna | Qué significa | Valor de referencia (binario nativo, 100 terminales, 60 s) |
|---|---|---|
| **#Samples** | Transacciones ISO enviadas y respondidas | 31.089 |
| **FAIL / Error %** | Respuestas que no fueron `0210` con DE 39 = 00, o que no llegaron | **0 / 0,00 %** |
| **Average / Median** | Latencia media y mediana (envío de la trama → respuesta) | 184,6 ms / 176 ms |
| **90th / 95th / 99th pct** | El 90 / 95 / 99 % de las transacciones respondió en menos de ese tiempo | 296 / **335** / 459 ms |
| **Max** | Peor caso (suele darse al inicio, mientras todo se calienta) | 993 ms |
| **Transactions/s** (grupo *Throughput*) | **TPS**: transacciones completadas por segundo | **518,6** |

Con 200 terminales (el valor por defecto del perfil `carga`) el resultado fue 514 TPS, 0 errores y p95 / p99 de 781 / 1.143 ms: con el doble de terminales el TPS no sube porque la CPU ya está al máximo, y cada transacción espera más su turno (más latencia).

Gráficas útiles (menú **Charts**):

- **Over Time → Active Threads Over Time:** las terminales conectadas (subida durante la rampa y estable después).
- **Throughput → Transactions Per Second:** el TPS se mantiene plano: el sistema no se degrada con el tiempo.
- **Over Time → Response Time Percentiles Over Time (successful responses):** las latencias se mantienen estables.
- **Throughput → Codes Per Second:** el plan guarda el **DE 39** como código de respuesta de cada muestra: solo aparece `00`.
- **Response Times → Response Time Distribution:** cuántas transacciones cayeron en cada rango de tiempo.

### 2. Reporte de Java Flight Recorder (`reportes\reporte-carga-jfr.txt`)

Lo genera `LoadTestSimulationTest` al correr las pruebas (A1): 100 terminales por TCP sobre la JVM con Generational ZGC mientras JFR registra el GC y el pinning. Ejemplo real:

```
Terminales concurrentes : 100 (1 conexión keep-alive cada una)
Transacciones medidas   : 5000 (+ 1000 de calentamiento)
Aprobadas / Fallidas    : 5000 / 0
Throughput              : 391.3 TPS
Latencia p50 / p95 / p99: 222.93 / 509.98 / 654.11 ms
Recolector de basura    : ZGC Major, ZGC Minor
Ciclos de GC medidos    : 8
Pausa máxima de GC      : 0.102 ms (objetivo <= 1.0 ms)
Eventos de pinning      : 5 (en código de la aplicación o >= 20 ms: 0)
```

- **Pausa máxima de GC 0,102 ms:** la aplicación se detuvo como máximo una décima de milisegundo para el GC; la meta era ≤ 1 ms. ZGC hace casi todo su trabajo en paralelo a la aplicación.
- **Pinning:** JFR registra hasta los bloqueos de microsegundos dentro del propio JDK (los 5 eventos). La prueba falla si hay pinning en código de la aplicación o de librerías, o de 20 ms o más: **0**. En las rutas de E/S el código usa `ReentrantLock` y estructuras sin bloqueo (`ConcurrentHashMap`) en lugar de `synchronized`; además, desde Java 24 (JEP 491) `synchronized` ya no fija el hilo portador en la mayoría de los casos.
- El TPS de esta prueba no es comparable con el de JMeter y varía entre ejecuciones (≈ 390 – 520 TPS): corre sobre H2 en memoria y el generador de carga está dentro de la misma JVM. Su objetivo es medir el GC y el pinning; el TPS oficial es el de JMeter.

**En vivo (opcional):** con `app-jvm` levantado, `set CARGA_HOST=app-jvm` y luego `docker compose --profile carga run --rm jmeter`; después `docker compose logs app-jvm | findstr "Pause"` muestra cada pausa real de ZGC, por ejemplo `GC(3) Y: Pause Mark End 0.107ms`: todas muy por debajo de 1 ms.

### 3. Puntos clave para explicar

1. **Cero errores** con 200 terminales concurrentes: ninguna transacción perdida, duplicada ni rechazada por el sistema.
2. **≈ 515 TPS sostenidos en 4 vCPU compartidas**: el límite es la CPU (la app, SQL Server y JMeter compiten por los mismos 4 núcleos). Cada transacción hace parseo ISO + llamada HTTP al Core + transacción SQL con bloqueo de fila e inserción + respuesta ISO. Los 2.000 TPS requieren más núcleos o separar el generador de carga y la base de datos.
3. **Latencias estables** durante toda la prueba (p95 ≈ 335 ms con 100 terminales): no hay degradación ni acumulación.
4. **Virtual Threads:** 200 conexiones abiertas sin crear 200 hilos del sistema operativo, y sin pinning.
5. **ZGC:** pausas de ≈ 0,1 ms, diez veces por debajo de la meta.
6. **Nativo vs JVM:** el mismo TPS (518,6 vs 516,4), pero el nativo arranca ≈ 15 – 20 veces más rápido y usa ≈ 8 veces menos RAM: ideal para escalar en contenedores.

---

# Parte C. Lista para el día de la defensa

**El día anterior** (con internet, para que no se descargue nada durante la presentación):

1. Con `APP_DOCKERFILE=Dockerfile` en `.env`: `docker compose up -d --build` (nativo) y `docker compose --profile comparacion up -d --build app-jvm`.
2. `docker compose --profile tests run --rm --build tests` → quedan `reportes\jacoco` y `reportes\reporte-carga-jfr.txt`.
3. `docker compose --profile carga run --rm jmeter` → queda `reportes\jmeter\html`.
4. Ensayar la demo B1 una vez (descarga la imagen del simulador) y restaurar los datos al terminar.

**30 minutos antes:**

1. Abrir Docker Desktop y esperar a que esté en verde.
2. `docker compose up -d` y `docker compose --profile comparacion up -d app-jvm`.
3. `docker compose ps` → `cajero-app` y `cajero-app-jvm` *healthy*.
4. Restaurar los datos: `curl -u admin:Nexus2026_Admin -X POST http://localhost:8087/api/core/reset`.
5. `docker compose restart app` (para que el arranque medido sea el "en caliente").
6. Dejar abiertos: una ventana de CMD en la carpeta del proyecto, `reportes\jmeter\html\index.html`, `reportes\reporte-carga-jfr.txt`, `reportes\jacoco\index.html` y http://localhost:8087.

**Orden sugerido:** B1 (transacciones en vivo) → B2 (binario nativo, arranque y RAM) → B3 (métricas). Plan B si algo falla en vivo: los reportes ya generados y las capturas del manual de usuario.

---

# Parte D. Problemas frecuentes

| Problema | Solución |
|---|---|
| El simulador dice `No se pudo comunicar con app:15000` | La app no está *healthy*: `docker compose ps` y `docker compose logs app` |
| `dependency failed to start: container cajero-app is unhealthy` | La app no arrancó (por ejemplo, SQL Server aún iniciando). Revisa `docker compose logs app`, espera un minuto y repite |
| El retiro responde `55` con el PIN correcto | Se escribió mal la cuenta o el PIN; revisa la tabla de cuentas. Tras 3 PIN incorrectos responde `75`: restaura los datos |
| Los saldos no coinciden con la tabla | Restaura los datos (sección 0) |
| `port is already allocated` al levantar `app-jvm` | Otro programa usa el 8088 o el 15001: cámbialos en `.env` (`APP_JVM_HTTP_PORT`, `APP_JVM_TCP_PORT`) |
| JMeter muestra errores con 200 terminales en una PC lenta | Baja la carga: `set CARGA_TERMINALES=50`. Los errores por timeout indican que la PC no da abasto, no un fallo de la lógica |
| `curl` en PowerShell pide parámetros o responde raro | En PowerShell usa `curl.exe` |
| El primer arranque tarda varios segundos | Normal tras encender la PC; mide con `docker compose restart app` |

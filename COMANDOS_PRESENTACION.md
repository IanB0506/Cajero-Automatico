# Comandos para la presentación — RED NEXUS

Lista de comandos de la defensa, en el orden en que se usan. Todos se probaron en ese orden. Los dos marcados *(falla a propósito)* deben dar error: así se demuestra que la imagen no tiene shell ni Java.

**Para que no fallen:**

- Usa **CMD (Símbolo del sistema)** dentro de la carpeta del proyecto, no PowerShell.
- En `.env` debe decir `APP_DOCKERFILE=Dockerfile` (versión nativa).
- Haz `git pull` antes de preparar todo: el reporte de ZGC necesita el código nuevo.
- No pongas el PIN incorrecto 3 veces seguidas: la tarjeta se bloquea hasta restaurar los datos (comando 9).

Explicación de cada demostración: [PRUEBAS.md](PRUEBAS.md).

---

## El día anterior (con internet)

**1. Compilar y levantar el nativo**
```cmd
docker compose up -d --build
```

**2. Compilar y levantar la versión JVM**
```cmd
docker compose --profile comparacion up -d --build app-jvm
```

**3. Pruebas, cobertura y reporte de ZGC**
```cmd
docker compose --profile tests run --rm --build tests
```

**4. Prueba de carga (reporte JMeter)**
```cmd
docker compose --profile carga run --rm jmeter
```

**5. Descargar el simulador**
```cmd
docker compose --profile demo run --rm simulador eco
```

## 30 minutos antes

**6. Levantar todo**
```cmd
docker compose up -d
```

**7. Levantar la JVM**
```cmd
docker compose --profile comparacion up -d app-jvm
```

**8. Ver que todo esté *healthy***
```cmd
docker compose ps
```

**9. Restaurar datos**
```cmd
curl -u admin:Nexus2026_Admin -X POST http://localhost:8087/api/core/reset
```

---

## Demostración 1 · Transacciones en vivo

**10. Retiro aprobado (00, saldo Q1,400)**
```cmd
docker compose --profile demo run --rm simulador retiro --monto 100 --rrn 100000000001
```

**11. Retransmisión (misma autorización, un solo cobro)**
```cmd
docker compose --profile demo run --rm simulador retiro --monto 100 --rrn 100000000001 --repetir 2
```

**12. Reversa (0430, saldo Q1,500)**
```cmd
docker compose --profile demo run --rm simulador reversa --monto 100 --rrn 100000000001
```

**13. PIN incorrecto (55)**
```cmd
docker compose --profile demo run --rm simulador retiro --pin 0000
```

**14. Consulta de saldo (0110, Q350)**
```cmd
docker compose --profile demo run --rm simulador consulta --cuenta 1001-2002-3002 --pin 4321
```

**15. Fondos insuficientes (51)**
```cmd
docker compose --profile demo run --rm simulador retiro --cuenta 1001-2002-3005 --pin 1111 --monto 50
```

**16. Echo test (0810)**
```cmd
docker compose --profile demo run --rm simulador eco
```

**17. Saldo en el Core**
```cmd
curl http://localhost:8087/api/core/cuentas/1001-2002-3001
```

**18. Historial de la cuenta**
```cmd
curl http://localhost:8087/api/core/cuentas/1001-2002-3001/transacciones
```

**19. Logs del Recolector**
```cmd
docker compose logs --tail 30 app
```

---

## Demostración 2 · Binario nativo, arranque y RAM

**20. Tamaño de la imagen (≈ 339 MB, columna SIZE o DISK USAGE)**
```cmd
docker images cajero-automatico-app
```

**21. Capas (binario de 226 MB sobre Distroless)**
```cmd
docker history cajero-automatico-app
```

**22. Usuario sin privilegios y ejecutable**
```cmd
docker image inspect cajero-automatico-app --format "Usuario={{.Config.User}} Entrypoint={{.Config.Entrypoint}}"
```

**23. No tiene shell** *(falla a propósito)*
```cmd
docker exec cajero-app sh
```

**24. No tiene Java** *(falla a propósito)*
```cmd
docker exec cajero-app java -version
```

**25. Un solo proceso, el binario**
```cmd
docker top cajero-app
```

**26. Confirmar que es nativo**
```cmd
docker compose logs app | findstr "AOT-processed"
```

**27. Reiniciar el nativo**
```cmd
docker compose restart app
```

**28. Tiempo de arranque (última línea, ≈ 0,5 s)**
```cmd
docker compose logs app | findstr "Started"
```

**29. Métricas y RAM del nativo**
```cmd
curl http://localhost:8087/api/iso/metricas
```

**30. Reiniciar la JVM (espera unos 10 s antes del siguiente)**
```cmd
docker compose restart app-jvm
```

**31. Tiempo de arranque de la JVM (≈ 8 s)**
```cmd
docker compose logs app-jvm | findstr "Started"
```

**32. RAM del nativo vs la JVM**
```cmd
docker stats --no-stream cajero-app cajero-app-jvm
```

---

## Demostración 3 · Métricas de la prueba de carga

**33. Reporte de JMeter**
```cmd
start reportes\jmeter\html\index.html
```

**34. Reporte de pausas de ZGC y pinning**
```cmd
notepad reportes\reporte-carga-jfr.txt
```

**35. Cobertura de pruebas**
```cmd
start reportes\jacoco\index.html
```

**36. Pausas de ZGC en vivo**
```cmd
docker compose logs app-jvm | findstr "Pause"
```

---

**Si algo falla en vivo:** `docker compose ps` para ver el estado y `docker compose logs app` para ver el error.

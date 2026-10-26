package com.banco.recolector.corebancario.controller;

import com.banco.recolector.corebancario.CuentaDto;
import com.banco.recolector.corebancario.TransaccionCoreRequest;
import com.banco.recolector.corebancario.TransaccionCoreResponse;
import com.banco.recolector.corebancario.entity.TransaccionEntity;
import com.banco.recolector.corebancario.service.CoreBancarioService;
import com.banco.recolector.iso.IsoResponseCode;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controlador REST que expone la API del Core Bancario.
 * Recibe transacciones financieras y retorna respuestas normalizadas con códigos HTTP semánticos.
 */
@RestController
@RequestMapping("/api/core")
@CrossOrigin(origins = "*")
public class CoreBancarioController {

    private final CoreBancarioService coreService;

    public CoreBancarioController(CoreBancarioService coreService) {
        this.coreService = coreService;
    }

    @PostMapping("/transacciones")
    public ResponseEntity<TransaccionCoreResponse> procesarTransaccion(@Valid @RequestBody TransaccionCoreRequest request) {
        TransaccionCoreResponse resp = coreService.procesar(request);

        // Mapeo semántico a códigos HTTP estándar según el resultado
        if (resp.exitosa()) {
            return ResponseEntity.ok(resp);
        }

        HttpStatus status = switch (resp.codigoRespuesta()) {
            case IsoResponseCode.CUENTA_INVALIDA -> HttpStatus.NOT_FOUND;         // 404
            case IsoResponseCode.TARJETA_BLOQUEADA -> HttpStatus.FORBIDDEN;      // 403
            case IsoResponseCode.FONDOS_INSUFICIENTES -> HttpStatus.UNPROCESSABLE_ENTITY; // 422
            case IsoResponseCode.TARJETA_EXPIRADA -> HttpStatus.GONE;             // 410
            case IsoResponseCode.LIMITE_EXCEDIDO -> HttpStatus.TOO_MANY_REQUESTS; // 429
            default -> HttpStatus.BAD_REQUEST;                                    // 400
        };

        return ResponseEntity.status(status).body(resp);
    }

    @GetMapping("/cuentas")
    public ResponseEntity<List<CuentaDto>> listarCuentas() {
        return ResponseEntity.ok(coreService.listarCuentas());
    }

    @PostMapping("/cuentas")
    public ResponseEntity<?> crearCuenta(@RequestBody com.banco.recolector.corebancario.CrearCuentaRequest request) {
        try {
            CuentaDto creada = coreService.crearCuenta(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(creada);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(java.util.Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/cuentas/{id}")
    public ResponseEntity<CuentaDto> obtenerCuenta(@PathVariable String id) {
        return coreService.obtenerCuenta(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/cuentas/{id}")
    public ResponseEntity<?> editarCuenta(@PathVariable String id, @RequestBody com.banco.recolector.corebancario.EditarCuentaRequest request) {
        return coreService.editarCuenta(id, request)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/cuentas/{id}")
    public ResponseEntity<Void> eliminarCuenta(@PathVariable String id) {
        boolean eliminada = coreService.eliminarCuenta(id);
        return eliminada ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @GetMapping("/transacciones")
    public ResponseEntity<List<TransaccionEntity>> listarTransacciones() {
        return ResponseEntity.ok(coreService.listarTransacciones());
    }

    @PostMapping("/cuentas/{id}/recargar")
    public ResponseEntity<CuentaDto> recargarCuenta(
            @PathVariable String id,
            @RequestParam(defaultValue = "500.00") java.math.BigDecimal monto) {
        return coreService.recargarSaldo(id, monto)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/cuentas/{id}/toggle-bloqueo")
    public ResponseEntity<CuentaDto> toggleBloqueo(@PathVariable String id) {
        return coreService.toggleBloqueo(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/reset")
    public ResponseEntity<String> resetCuentas() {
        coreService.resetCuentas();
        return ResponseEntity.ok("Cuentas restauradas al estado inicial.");
    }
}

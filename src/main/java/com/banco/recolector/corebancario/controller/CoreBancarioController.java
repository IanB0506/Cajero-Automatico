package com.banco.recolector.corebancario.controller;

import com.banco.recolector.corebancario.CrearCuentaRequest;
import com.banco.recolector.corebancario.CuentaDto;
import com.banco.recolector.corebancario.EditarCuentaRequest;
import com.banco.recolector.corebancario.TransaccionCoreRequest;
import com.banco.recolector.corebancario.TransaccionCoreResponse;
import com.banco.recolector.corebancario.entity.TransaccionEntity;
import com.banco.recolector.corebancario.service.CoreBancarioService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Positive;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static com.banco.recolector.iso.IsoResponseCode.*;

/**
 * API REST del Core Bancario.
 * Recibe transacciones financieras y retorna respuestas normalizadas con códigos HTTP semánticos.
 * Seguridad (ver SeguridadConfig): POST /transacciones exige la API Key del Recolector; las operaciones
 * de administración exigen el rol ADMIN; las consultas GET son públicas para el simulador.
 */
@RestController
@RequestMapping("/api/core")
public class CoreBancarioController {

    private final CoreBancarioService coreService;

    public CoreBancarioController(CoreBancarioService coreService) {
        this.coreService = coreService;
    }

    @PostMapping("/transacciones")
    public ResponseEntity<TransaccionCoreResponse> procesarTransaccion(@Valid @RequestBody TransaccionCoreRequest request) {
        TransaccionCoreResponse resp = coreService.procesar(request);
        return ResponseEntity.status(estadoHttp(resp)).body(resp);
    }

    /**
     * Mapeo semántico del código ISO (DE 39) a un código HTTP; el cuerpo siempre incluye el DE 39 exacto.
     */
    static HttpStatusCode estadoHttp(TransaccionCoreResponse resp) {
        if (resp.exitosa()) {
            return HttpStatus.OK;
        }
        return switch (resp.codigoRespuesta()) {
            case CUENTA_INVALIDA, ORIGINAL_NO_ENCONTRADA -> HttpStatus.NOT_FOUND;                  // 404
            case TARJETA_BLOQUEADA, PIN_INCORRECTO, INTENTOS_PIN_EXCEDIDOS -> HttpStatus.FORBIDDEN; // 403
            case FONDOS_INSUFICIENTES -> HttpStatusCode.valueOf(422);                             // 422
            case TARJETA_EXPIRADA -> HttpStatus.GONE;                                             // 410
            case LIMITE_EXCEDIDO -> HttpStatus.TOO_MANY_REQUESTS;                                 // 429
            default -> HttpStatus.BAD_REQUEST;                                                    // 400: 12, 13, 30
        };
    }

    @GetMapping("/cuentas")
    public List<CuentaDto> listarCuentas() {
        return coreService.listarCuentas();
    }

    @PostMapping("/cuentas")
    public ResponseEntity<?> crearCuenta(@Valid @RequestBody CrearCuentaRequest request) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(coreService.crearCuenta(request));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "La cuenta o la tarjeta ya existe"));
        }
    }

    @GetMapping("/cuentas/{id}")
    public ResponseEntity<CuentaDto> obtenerCuenta(@PathVariable String id) {
        return ResponseEntity.of(coreService.obtenerCuenta(id));
    }

    @GetMapping("/cuentas/{id}/transacciones")
    public ResponseEntity<List<TransaccionEntity>> transaccionesDeCuenta(
            @PathVariable String id,
            @RequestParam(defaultValue = "50") int limite) {
        return ResponseEntity.of(coreService.transaccionesDeCuenta(id, limite));
    }

    @PutMapping("/cuentas/{id}")
    public ResponseEntity<CuentaDto> editarCuenta(@PathVariable String id, @Valid @RequestBody EditarCuentaRequest request) {
        return ResponseEntity.of(coreService.editarCuenta(id, request));
    }

    @DeleteMapping("/cuentas/{id}")
    public ResponseEntity<Void> eliminarCuenta(@PathVariable String id) {
        return coreService.eliminarCuenta(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @GetMapping("/transacciones")
    public List<TransaccionEntity> listarTransacciones(@RequestParam(defaultValue = "200") int limite) {
        return coreService.listarTransacciones(limite);
    }

    @PostMapping("/cuentas/{id}/recargar")
    public ResponseEntity<CuentaDto> recargarCuenta(
            @PathVariable String id,
            @RequestParam(defaultValue = "500.00")
            @Positive(message = "El monto de la recarga debe ser mayor que cero")
            @Digits(integer = 13, fraction = 2, message = "El monto admite 2 decimales") BigDecimal monto) {
        return ResponseEntity.of(coreService.recargarSaldo(id, monto));
    }

    @PostMapping("/cuentas/{id}/toggle-bloqueo")
    public ResponseEntity<CuentaDto> toggleBloqueo(@PathVariable String id) {
        return ResponseEntity.of(coreService.toggleBloqueo(id));
    }

    @PostMapping("/reset")
    public ResponseEntity<String> resetCuentas() {
        coreService.resetCuentas();
        return ResponseEntity.ok("Cuentas restauradas al estado inicial.");
    }

    /** Verifica las credenciales de administrador (lo usa el login de la interfaz web). */
    @GetMapping("/admin/sesion")
    public Map<String, String> sesionAdmin(Authentication autenticacion) {
        return Map.of("usuario", autenticacion.getName(), "rol", "ADMIN");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<?> solicitudInvalida(Exception e, HttpServletRequest request) {
        String mensaje = e instanceof MethodArgumentNotValidException invalida && invalida.getBindingResult().getFieldError() != null
                ? invalida.getBindingResult().getFieldError().getDefaultMessage()
                : "Cuerpo de la solicitud ilegible o con formato inválido";
        if (request.getRequestURI().endsWith("/transacciones")) {
            // El Recolector traduce este rechazo a DE 39 = 30 (Error de Formato)
            return ResponseEntity.badRequest().body(TransaccionCoreResponse.rechazada(
                    null, ERROR_FORMATO, mensaje, BigDecimal.ZERO, BigDecimal.ZERO));
        }
        return ResponseEntity.badRequest().body(Map.of("error", mensaje));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Map<String, String>> parametroInvalido(HandlerMethodValidationException e) {
        String mensaje = e.getAllErrors().stream().findFirst()
                .map(error -> error.getDefaultMessage()).orElse("Parámetro inválido");
        return ResponseEntity.badRequest().body(Map.of("error", mensaje));
    }
}

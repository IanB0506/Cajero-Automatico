package com.banco.recolector.corebancario.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Entidad JPA que representa la tabla de Transacciones del Core Bancario,
 * donde se registran todos los débitos y créditos derivados de mensajes ISO 8583.
 */
@Entity
@Table(name = "transacciones", indexes = {
    @Index(name = "idx_tx_rrn", columnList = "rrn"),
    @Index(name = "idx_tx_stan", columnList = "stan")
})
public class TransaccionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 12)
    private String rrn;

    @Column(nullable = false, length = 6)
    private String stan;

    @Column(nullable = false, length = 30)
    private String numeroCuenta;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal monto;

    @Column(nullable = false, length = 20)
    private String tipoOperacion; // DEBITO, CREDITO, CONSULTA, REVERSA

    @Column(precision = 15, scale = 2)
    private BigDecimal saldoAnterior;

    @Column(precision = 15, scale = 2)
    private BigDecimal saldoPosterior;

    @Column(nullable = false, length = 2)
    private String codigoRespuesta; // 00, 51, etc.

    @Column(length = 6)
    private String codigoAutorizacion;

    @Column(length = 30)
    private String terminalId;

    @Column(nullable = false)
    private LocalDateTime fechaHora;

    public TransaccionEntity() {}

    public TransaccionEntity(String rrn, String stan, String numeroCuenta, BigDecimal monto,
                             String tipoOperacion, BigDecimal saldoAnterior, BigDecimal saldoPosterior,
                             String codigoRespuesta, String codigoAutorizacion, String terminalId,
                             LocalDateTime fechaHora) {
        this.rrn = rrn;
        this.stan = stan;
        this.numeroCuenta = numeroCuenta;
        this.monto = monto;
        this.tipoOperacion = tipoOperacion;
        this.saldoAnterior = saldoAnterior;
        this.saldoPosterior = saldoPosterior;
        this.codigoRespuesta = codigoRespuesta;
        this.codigoAutorizacion = codigoAutorizacion;
        this.terminalId = terminalId;
        this.fechaHora = fechaHora;
    }

    public Long getId() { return id; }
    public String getRrn() { return rrn; }
    public String getStan() { return stan; }
    public String getNumeroCuenta() { return numeroCuenta; }
    public BigDecimal getMonto() { return monto; }
    public String getTipoOperacion() { return tipoOperacion; }
    public BigDecimal getSaldoAnterior() { return saldoAnterior; }
    public BigDecimal getSaldoPosterior() { return saldoPosterior; }
    public String getCodigoRespuesta() { return codigoRespuesta; }
    public String getCodigoAutorizacion() { return codigoAutorizacion; }
    public String getTerminalId() { return terminalId; }
    public LocalDateTime getFechaHora() { return fechaHora; }
}

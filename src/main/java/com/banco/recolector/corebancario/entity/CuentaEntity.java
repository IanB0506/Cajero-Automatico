package com.banco.recolector.corebancario.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Entidad JPA que representa la tabla de Cuentas del Core Bancario.
 * El PIN nunca se almacena en claro: solo su hash SHA-256 (ver {@code PinHasher}).
 */
@Entity
@Table(name = "cuentas", indexes = {
    @Index(name = "idx_cuenta_num", columnList = "numeroCuenta", unique = true),
    @Index(name = "idx_cuenta_pan", columnList = "pan")
})
public class CuentaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String numeroCuenta;

    @Column(nullable = false, length = 19)
    private String pan;

    @Column(nullable = false, length = 100)
    private String titular;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal saldoDisponible;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal saldoContable;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal limiteDiario;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal retiradoHoy;

    /** Día al que corresponde {@link #retiradoHoy}; al cambiar el día el acumulado vuelve a cero. */
    private LocalDate fechaUltimoRetiro;

    @Column(nullable = false, length = 20)
    private String estado; // ACTIVE, BLOCKED, EXPIRED

    @Column(length = 64)
    private String pinHash;

    @Column(nullable = false)
    private int intentosPinFallidos;

    @Column(length = 60)
    private String banco;

    protected CuentaEntity() {}

    public CuentaEntity(String numeroCuenta, String pan, String titular, BigDecimal saldo,
                        BigDecimal limiteDiario, String estado, String pinHash, String banco) {
        this.numeroCuenta = numeroCuenta;
        this.pan = pan;
        this.titular = titular;
        this.saldoDisponible = saldo;
        this.saldoContable = saldo;
        this.limiteDiario = limiteDiario;
        this.retiradoHoy = BigDecimal.ZERO;
        this.estado = estado;
        this.pinHash = pinHash;
        this.banco = banco;
    }

    public Long getId() { return id; }
    public String getNumeroCuenta() { return numeroCuenta; }
    public String getPan() { return pan; }
    public String getTitular() { return titular; }
    public void setTitular(String titular) { this.titular = titular; }
    public BigDecimal getSaldoDisponible() { return saldoDisponible; }
    public void setSaldoDisponible(BigDecimal saldoDisponible) { this.saldoDisponible = saldoDisponible; }
    public BigDecimal getSaldoContable() { return saldoContable; }
    public void setSaldoContable(BigDecimal saldoContable) { this.saldoContable = saldoContable; }
    public BigDecimal getLimiteDiario() { return limiteDiario; }
    public void setLimiteDiario(BigDecimal limiteDiario) { this.limiteDiario = limiteDiario; }
    public BigDecimal getRetiradoHoy() { return retiradoHoy; }
    public void setRetiradoHoy(BigDecimal retiradoHoy) { this.retiradoHoy = retiradoHoy; }
    public LocalDate getFechaUltimoRetiro() { return fechaUltimoRetiro; }
    public void setFechaUltimoRetiro(LocalDate fechaUltimoRetiro) { this.fechaUltimoRetiro = fechaUltimoRetiro; }
    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }
    public String getPinHash() { return pinHash; }
    public void setPinHash(String pinHash) { this.pinHash = pinHash; }
    public int getIntentosPinFallidos() { return intentosPinFallidos; }
    public void setIntentosPinFallidos(int intentosPinFallidos) { this.intentosPinFallidos = intentosPinFallidos; }
    public String getBanco() { return (banco != null && !banco.isBlank()) ? banco : "BANCO INDUSTRIAL"; }
    public void setBanco(String banco) { this.banco = banco; }

    /** Acredita o debita (monto negativo) el saldo disponible y contable. */
    public void aplicarMovimiento(BigDecimal monto) {
        this.saldoDisponible = this.saldoDisponible.add(monto);
        this.saldoContable = this.saldoContable.add(monto);
    }
}

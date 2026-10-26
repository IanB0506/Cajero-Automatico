package com.banco.recolector.corebancario.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;

/**
 * Entidad JPA que representa la tabla de Cuentas del Core Bancario.
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

    @Column(nullable = false, length = 20)
    private String estado; // ACTIVE, BLOCKED, EXPIRED

    @Column(nullable = false, length = 10)
    private String pin = "1234";

    @Column(length = 60)
    private String banco = "BANCO INDUSTRIAL";

    public CuentaEntity() {}

    public CuentaEntity(String numeroCuenta, String pan, String titular, BigDecimal saldoDisponible,
                        BigDecimal saldoContable, BigDecimal limiteDiario, BigDecimal retiradoHoy, String estado, String pin, String banco) {
        this.numeroCuenta = numeroCuenta;
        this.pan = pan;
        this.titular = titular;
        this.saldoDisponible = saldoDisponible;
        this.saldoContable = saldoContable;
        this.limiteDiario = limiteDiario;
        this.retiradoHoy = retiradoHoy;
        this.estado = estado;
        this.pin = (pin != null && !pin.isBlank()) ? pin : "1234";
        this.banco = (banco != null && !banco.isBlank()) ? banco : "BANCO INDUSTRIAL";
    }

    public CuentaEntity(String numeroCuenta, String pan, String titular, BigDecimal saldoDisponible,
                        BigDecimal saldoContable, BigDecimal limiteDiario, BigDecimal retiradoHoy, String estado, String pin) {
        this(numeroCuenta, pan, titular, saldoDisponible, saldoContable, limiteDiario, retiradoHoy, estado, pin, "BANCO INDUSTRIAL");
    }

    public CuentaEntity(String numeroCuenta, String pan, String titular, BigDecimal saldoDisponible,
                        BigDecimal saldoContable, BigDecimal limiteDiario, BigDecimal retiradoHoy, String estado) {
        this(numeroCuenta, pan, titular, saldoDisponible, saldoContable, limiteDiario, retiradoHoy, estado, "1234", "BANCO INDUSTRIAL");
    }

    public Long getId() { return id; }
    public String getNumeroCuenta() { return numeroCuenta; }
    public void setNumeroCuenta(String numeroCuenta) { this.numeroCuenta = numeroCuenta; }
    public String getPan() { return pan; }
    public void setPan(String pan) { this.pan = pan; }
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
    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }
    public String getPin() { return pin != null ? pin : "1234"; }
    public void setPin(String pin) { this.pin = (pin != null && !pin.isBlank()) ? pin : "1234"; }
    public String getBanco() { return (banco != null && !banco.isBlank()) ? banco : "BANCO INDUSTRIAL"; }
    public void setBanco(String banco) { this.banco = banco; }
}

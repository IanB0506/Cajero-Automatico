package com.banco.recolector.corebancario.repository;

import com.banco.recolector.corebancario.entity.CuentaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CuentaRepository extends JpaRepository<CuentaEntity, Long> {

    Optional<CuentaEntity> findByNumeroCuenta(String numeroCuenta);

    boolean existsByNumeroCuenta(String numeroCuenta);

    boolean existsByPan(String pan);

    Optional<CuentaEntity> findFirstByPanOrderByIdAsc(String pan);

    /** Identificador de la cuenta por número de cuenta (sin cargar ni bloquear la entidad). */
    @Query("select c.id from CuentaEntity c where c.numeroCuenta = :numeroCuenta")
    Optional<Long> buscarIdPorNumero(@Param("numeroCuenta") String numeroCuenta);

    /** Identificadores de cuentas asociadas a un PAN (sin cargar ni bloquear la entidad). */
    @Query("select c.id from CuentaEntity c where c.pan = :pan order by c.id")
    List<Long> buscarIdsPorPan(@Param("pan") String pan);

    /**
     * Bloqueo pesimista de la fila por clave primaria (SELECT ... WITH (UPDLOCK, HOLDLOCK, ROWLOCK)
     * en SQL Server, FOR UPDATE en H2): serializa débitos concurrentes sobre la misma cuenta.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CuentaEntity c where c.id = :id")
    Optional<CuentaEntity> bloquearPorId(@Param("id") Long id);

    /** Búsqueda y bloqueo pesimista en una sola consulta (seek sobre el índice único del número de cuenta). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CuentaEntity c where c.numeroCuenta = :numeroCuenta")
    Optional<CuentaEntity> bloquearPorNumero(@Param("numeroCuenta") String numeroCuenta);
}

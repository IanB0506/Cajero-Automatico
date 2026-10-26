package com.banco.recolector.corebancario.repository;

import com.banco.recolector.corebancario.entity.CuentaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CuentaRepository extends JpaRepository<CuentaEntity, Long> {
    Optional<CuentaEntity> findByNumeroCuenta(String numeroCuenta);
    Optional<CuentaEntity> findByPan(String pan);
    Optional<CuentaEntity> findByNumeroCuentaOrPan(String numeroCuenta, String pan);
}

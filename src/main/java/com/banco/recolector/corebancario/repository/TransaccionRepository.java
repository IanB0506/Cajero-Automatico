package com.banco.recolector.corebancario.repository;

import com.banco.recolector.corebancario.entity.TransaccionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TransaccionRepository extends JpaRepository<TransaccionEntity, Long> {
    Optional<TransaccionEntity> findByRrnAndStan(String rrn, String stan);
    List<TransaccionEntity> findByNumeroCuentaOrderByFechaHoraDesc(String numeroCuenta);
}

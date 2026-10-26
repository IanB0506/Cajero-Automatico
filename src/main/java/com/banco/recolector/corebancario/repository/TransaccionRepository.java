package com.banco.recolector.corebancario.repository;

import com.banco.recolector.corebancario.entity.TransaccionEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface TransaccionRepository extends JpaRepository<TransaccionEntity, Long> {

    Optional<TransaccionEntity> findByRrnAndStan(String rrn, String stan);

    @Query("select t from TransaccionEntity t order by t.id desc")
    List<TransaccionEntity> buscarRecientes(Pageable pagina);

    @Query("select t from TransaccionEntity t where t.numeroCuenta = :cuenta or t.cuentaDestino = :cuenta order by t.id desc")
    List<TransaccionEntity> buscarPorCuenta(@Param("cuenta") String numeroCuenta, Pageable pagina);

    /** Transacción original aprobada (por RRN y cuenta) de los tipos indicados. */
    @Query("""
           select t from TransaccionEntity t
           where t.rrn = :rrn and t.numeroCuenta = :cuenta and t.tipoOperacion in :tipos and t.codigoRespuesta = '00'
           order by t.id asc
           """)
    List<TransaccionEntity> buscarOriginalesAprobadas(@Param("rrn") String rrn,
                                                      @Param("cuenta") String numeroCuenta,
                                                      @Param("tipos") Collection<String> tipos);

    /** Suma de montos aprobados de un tipo (reversas o devoluciones) ya aplicados sobre una transacción original. */
    @Query("""
           select coalesce(sum(t.monto), 0) from TransaccionEntity t
           where t.rrn = :rrn and t.numeroCuenta = :cuenta and t.tipoOperacion = :tipo and t.codigoRespuesta = '00'
           """)
    BigDecimal sumarAprobadas(@Param("rrn") String rrn,
                              @Param("cuenta") String numeroCuenta,
                              @Param("tipo") String tipoOperacion);

    @Query("select max(t.codigoAutorizacion) from TransaccionEntity t")
    String ultimoCodigoAutorizacion();
}

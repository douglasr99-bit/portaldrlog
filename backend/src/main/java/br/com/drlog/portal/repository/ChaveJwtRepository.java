package br.com.drlog.portal.repository;

import br.com.drlog.portal.model.ChaveJwt;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

public interface ChaveJwtRepository extends Repository<ChaveJwt, String> {

    Optional<ChaveJwt> findByAtivaTrue();

    /** Todas, para o JWKS: as antigas continuam verificando tokens em trânsito. */
    List<ChaveJwt> findAllByOrderByCriadaEmDesc();

    ChaveJwt save(ChaveJwt chave);

    long count();
}

package br.com.drlog.portal.repository;

import br.com.drlog.portal.model.CadastroRecusado;
import org.springframework.data.repository.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CadastroRecusadoRepository extends Repository<CadastroRecusado, UUID> {

    CadastroRecusado save(CadastroRecusado recusa);

    List<CadastroRecusado> findTop50ByOrderByCriadoEmDesc();

    long countByCriadoEmAfter(Instant desde);
}

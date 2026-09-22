package br.com.drlog.portal.repository;

import br.com.drlog.portal.model.Assinatura;
import br.com.drlog.portal.model.Cobranca;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CobrancaRepository extends Repository<Cobranca, UUID> {

    Optional<Cobranca> findByGatewayAndExternaId(String gateway, String externaId);

    List<Cobranca> findByAssinaturaOrderByVencimentoDesc(Assinatura assinatura);

    List<Cobranca> findAllByOrderByCriadaEmDesc();

    Cobranca save(Cobranca cobranca);
}

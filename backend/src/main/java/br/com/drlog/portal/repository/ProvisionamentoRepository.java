package br.com.drlog.portal.repository;

import br.com.drlog.portal.model.Produto;
import br.com.drlog.portal.model.Provisionamento;
import br.com.drlog.portal.model.Tenant;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProvisionamentoRepository extends Repository<Provisionamento, UUID> {

    Optional<Provisionamento> findByTenantAndProduto(Tenant tenant, Produto produto);

    Optional<Provisionamento> findById(UUID id);

    List<Provisionamento> findAllByOrderByCriadoEmDesc();

    boolean existsByInstancia(String instancia);

    Provisionamento save(Provisionamento p);
}

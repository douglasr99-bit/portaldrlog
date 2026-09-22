package br.com.drlog.portal.repository;

import br.com.drlog.portal.model.Assinatura;
import br.com.drlog.portal.model.Produto;
import br.com.drlog.portal.model.Tenant;
import org.springframework.data.repository.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssinaturaRepository extends Repository<Assinatura, UUID> {

    List<Assinatura> findByTenantInOrderByCriadaEmAsc(Collection<Tenant> tenants);

    /**
     * A consulta que o handoff da etapa 2 fará: esta conta, este produto,
     * tem direito de entrar?
     */
    Optional<Assinatura> findByTenantAndProduto(Tenant tenant, Produto produto);

    Assinatura save(Assinatura assinatura);
}

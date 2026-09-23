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

    Optional<Assinatura> findById(UUID id);

    /** Todas as assinaturas, para a tela de administração. */
    List<Assinatura> findAllByOrderByCriadaEmDesc();

    /** Para o webhook encontrar a assinatura a partir do id do gateway. */
    Optional<Assinatura> findByGatewayAndGatewayAssinaturaId(String gateway, String assinaturaId);

    /** Para a volta do checkout e o webhook encontrarem a assinatura. */
    Optional<Assinatura> findByGatewayCheckoutId(String checkoutId);

    /** Quantos testes estão em curso — a trava de capacidade olha para isto. */
    long countByEstado(br.com.drlog.portal.model.EstadoAssinatura estado);

    Assinatura save(Assinatura assinatura);
}

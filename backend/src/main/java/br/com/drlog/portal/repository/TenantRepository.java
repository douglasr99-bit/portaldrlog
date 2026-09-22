package br.com.drlog.portal.repository;

import br.com.drlog.portal.model.Tenant;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantRepository extends Repository<Tenant, UUID> {

    Optional<Tenant> findByCodigo(String codigo);

    boolean existsByCodigo(String codigo);

    /**
     * Todos os assinantes, para a tela de administração.
     *
     * Diferente dos repositórios do Styllus, aqui listar tudo é legítimo: os
     * tenants são o dado da própria plataforma, não o dado de um assinante.
     */
    List<Tenant> findAllByOrderByNomeAsc();

    Optional<Tenant> findById(UUID id);

    Tenant save(Tenant tenant);
}

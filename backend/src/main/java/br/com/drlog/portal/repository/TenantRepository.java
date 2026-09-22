package br.com.drlog.portal.repository;

import br.com.drlog.portal.model.Tenant;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

public interface TenantRepository extends Repository<Tenant, UUID> {

    Optional<Tenant> findByCodigo(String codigo);

    Optional<Tenant> findById(UUID id);

    Tenant save(Tenant tenant);
}

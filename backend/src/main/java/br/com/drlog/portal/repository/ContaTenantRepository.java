package br.com.drlog.portal.repository;

import br.com.drlog.portal.model.Conta;
import br.com.drlog.portal.model.ContaTenant;
import br.com.drlog.portal.model.Tenant;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContaTenantRepository extends Repository<ContaTenant, UUID> {

    List<ContaTenant> findByContaOrderByCriadoEmAsc(Conta conta);

    /**
     * Existe para responder "esta conta pode agir sobre este assinante?".
     *
     * É a verificação que impede alguém de operar sobre um tenant só por
     * conhecer o identificador dele — o mesmo raciocínio que levou o Styllus a
     * exigir findByIdAndTenantId em toda alteração.
     */
    Optional<ContaTenant> findByContaAndTenant(Conta conta, Tenant tenant);

    ContaTenant save(ContaTenant vinculo);
}

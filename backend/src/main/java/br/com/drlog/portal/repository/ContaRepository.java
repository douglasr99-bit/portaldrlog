package br.com.drlog.portal.repository;

import br.com.drlog.portal.model.Conta;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ContaRepository extends Repository<Conta, UUID> {

    /**
     * Busca pelo e-mail normalizado — é assim que o banco garante unicidade
     * (índice sobre lower(email)), então é assim que a busca precisa ser feita.
     * Procurar pela forma digitada deixaria "Joao@x.com" sem encontrar o
     * cadastro feito como "joao@x.com".
     */
    @Query("select c from Conta c where lower(c.email) = lower(:email)")
    Optional<Conta> buscarPorEmail(@Param("email") String email);

    Optional<Conta> findById(UUID id);

    Conta save(Conta conta);

    long count();
}

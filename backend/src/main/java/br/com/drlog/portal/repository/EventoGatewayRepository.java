package br.com.drlog.portal.repository;

import br.com.drlog.portal.model.EventoGateway;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EventoGatewayRepository extends Repository<EventoGateway, UUID> {

    boolean existsByGatewayAndEventoId(String gateway, String eventoId);

    Optional<EventoGateway> findById(UUID id);

    /** A fila do que chegou e ainda não foi tratado. */
    List<EventoGateway> findByProcessadoEmIsNullOrderByRecebidoEmAsc();

    List<EventoGateway> findTop30ByOrderByRecebidoEmDesc();

    EventoGateway save(EventoGateway evento);
}

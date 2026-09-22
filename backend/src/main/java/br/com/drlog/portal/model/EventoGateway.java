package br.com.drlog.portal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Um webhook recebido, guardado como chegou.
 *
 * Gravar primeiro e processar depois não é organização: é o que permite
 * responder 2xx rápido. O Asaas interrompe a fila após 15 falhas
 * consecutivas — um processamento lento ou com exceção dentro do handler não
 * atrasa um evento, para todos.
 */
@Entity
@Table(name = "eventos_gateway")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
@EqualsAndHashCode(of = "id")
public class EventoGateway {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String gateway;

    /** O id do evento no gateway. A unicidade com o gateway é a idempotência. */
    @Column(name = "evento_id", nullable = false)
    private String eventoId;

    private String tipo;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "recebido_em", nullable = false)
    private Instant recebidoEm;

    @Column(name = "processado_em")
    private Instant processadoEm;

    @Column(length = 500)
    private String erro;

    @PrePersist
    private void aoCriar() {
        if (recebidoEm == null) recebidoEm = Instant.now();
    }
}

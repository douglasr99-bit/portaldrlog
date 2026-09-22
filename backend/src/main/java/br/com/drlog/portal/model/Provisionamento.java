package br.com.drlog.portal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** A instância de WhatsApp de um assinante, para um produto. */
@Entity
@Table(name = "provisionamentos")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
@EqualsAndHashCode(of = "id")
public class Provisionamento {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "produto_id", nullable = false)
    private Produto produto;

    @Column(nullable = false)
    private String instancia;

    /** Credencial da instância. Nunca entra num token que passe pelo navegador. */
    @Column(length = 500)
    private String token;

    /** 'pendente', 'ativo' ou 'falhou'. */
    @Column(nullable = false)
    @Builder.Default
    private String estado = "pendente";

    /** O motivo da última falha, para a tela dizer o que aconteceu. */
    @Column(length = 500)
    private String erro;

    @Column(name = "criado_em", nullable = false)
    private Instant criadoEm;

    @Column(name = "atualizado_em", nullable = false)
    private Instant atualizadoEm;

    @PrePersist
    private void aoCriar() {
        Instant agora = Instant.now();
        if (criadoEm == null) criadoEm = agora;
        atualizadoEm = agora;
    }

    @PreUpdate
    private void aoAtualizar() { atualizadoEm = Instant.now(); }
}

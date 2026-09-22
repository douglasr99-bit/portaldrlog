package br.com.drlog.portal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** Pessoa que faz login no Portal. */
@Entity
@Table(name = "contas")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
@EqualsAndHashCode(of = "id")
public class Conta {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Guardado como digitado, para exibir do jeito que a pessoa escreveu.
     * A unicidade é garantida por índice sobre lower(email) no banco, e a
     * busca é feita pela forma normalizada.
     */
    @Column(nullable = false)
    private String email;

    @Column(name = "senha_hash", nullable = false)
    private String senhaHash;

    @Column(nullable = false)
    private String nome;

    @Column(nullable = false)
    @Builder.Default
    private boolean ativa = true;

    @Column(name = "criada_em", nullable = false)
    private Instant criadaEm;

    @PrePersist
    private void aoCriar() {
        if (criadaEm == null) criadaEm = Instant.now();
    }
}

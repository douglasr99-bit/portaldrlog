package br.com.drlog.portal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * O assinante — a quem pertencem os dados dentro de cada sistema vendido.
 *
 * Não se confunde com Conta: uma pessoa pode responder por mais de um
 * assinante, e um assinante pode ser acessado por mais de uma pessoa.
 */
@Entity
@Table(name = "tenants")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
@EqualsAndHashCode(of = "id")
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * O valor que viaja no claim tenant_id do token e que cada sistema grava
     * em toda linha dos seus dados. É este, e não o uuid, que aparece na
     * coluna tenant_id do Styllus.
     */
    @Column(nullable = false, length = 64)
    private String codigo;

    @Column(nullable = false)
    private String nome;

    private String documento;

    @Column(name = "criado_em", nullable = false)
    private Instant criadoEm;

    @PrePersist
    private void aoCriar() {
        if (criadoEm == null) criadoEm = Instant.now();
    }
}

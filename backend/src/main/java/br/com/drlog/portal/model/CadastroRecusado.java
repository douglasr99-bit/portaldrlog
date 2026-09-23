package br.com.drlog.portal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Uma tentativa de cadastro barrada.
 *
 * Existe para que barrar demais seja perceptível: uma trava apertada que
 * rejeita cliente de verdade é pior que o abuso que ela evita.
 */
@Entity
@Table(name = "cadastros_recusados")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
@EqualsAndHashCode(of = "id")
public class CadastroRecusado {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String ip;
    private String email;

    @Column(nullable = false)
    private String motivo;

    @Column(name = "criado_em", nullable = false)
    private Instant criadoEm;

    @PrePersist
    private void aoCriar() {
        if (criadoEm == null) criadoEm = Instant.now();
    }
}

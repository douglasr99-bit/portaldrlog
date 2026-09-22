package br.com.drlog.portal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Espelho local de uma cobrança do gateway.
 *
 * Espelho, e não fonte: a verdade é do gateway. Existe para a tela mostrar o
 * histórico sem depender de chamada externa, e para diagnosticar depois.
 */
@Entity
@Table(name = "cobrancas")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
@EqualsAndHashCode(of = "id")
public class Cobranca {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "assinatura_id", nullable = false)
    private Assinatura assinatura;

    @Column(nullable = false)
    private String gateway;

    /** O id da cobrança no gateway. */
    @Column(name = "externa_id", nullable = false)
    private String externaId;

    @Column(name = "valor_centavos", nullable = false)
    private int valorCentavos;

    private LocalDate vencimento;

    /** Como o gateway chama: PENDING, CONFIRMED, RECEIVED, OVERDUE… */
    @Column(nullable = false)
    private String status;

    @Column(name = "pago_em")
    private Instant pagoEm;

    /** A fatura, para reenviar ao cliente. */
    @Column(length = 500)
    private String link;

    @Column(name = "criada_em", nullable = false)
    private Instant criadaEm;

    @Column(name = "atualizada_em", nullable = false)
    private Instant atualizadaEm;

    public String valorFormatado() {
        return "R$ %d,%02d".formatted(valorCentavos / 100, valorCentavos % 100);
    }

    @PrePersist
    private void aoCriar() {
        Instant agora = Instant.now();
        if (criadaEm == null) criadaEm = agora;
        atualizadaEm = agora;
    }

    @PreUpdate
    private void aoAtualizar() { atualizadaEm = Instant.now(); }
}

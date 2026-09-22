package br.com.drlog.portal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "planos")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
@EqualsAndHashCode(of = "id")
public class Plano {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "produto_id", nullable = false)
    private Produto produto;

    @Column(nullable = false, length = 64)
    private String codigo;

    @Column(nullable = false)
    private String nome;

    /**
     * Inteiro, em centavos. Ponto flutuante acumula erro de arredondamento, e
     * o lugar onde esse erro aparece é a fatura do cliente.
     */
    @Column(name = "preco_centavos", nullable = false)
    private int precoCentavos;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Ciclo ciclo;

    @Column(nullable = false)
    @Builder.Default
    private boolean ativo = true;

    @Column(name = "criado_em", nullable = false)
    private Instant criadoEm;

    @PrePersist
    private void aoCriar() {
        if (criadoEm == null) criadoEm = Instant.now();
    }

    /** Preço formatado para exibição, sem espalhar divisão por 100 nas telas. */
    public String precoFormatado() {
        return "R$ %d,%02d".formatted(precoCentavos / 100, precoCentavos % 100);
    }
}

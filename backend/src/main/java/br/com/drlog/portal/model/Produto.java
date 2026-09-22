package br.com.drlog.portal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** Cada sistema à venda na plataforma. */
@Entity
@Table(name = "produtos")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
@EqualsAndHashCode(of = "id")
public class Produto {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Vira o claim "aud" do token emitido para este sistema. */
    @Column(nullable = false, length = 64)
    private String codigo;

    @Column(nullable = false)
    private String nome;

    @Column(length = 2000)
    private String descricao;

    /**
     * Uma frase concreta sobre o que o sistema faz, exibida na listagem.
     * Não é slogan — é o texto que faz o visitante se reconhecer ou seguir
     * adiante.
     */
    @Column(length = 500)
    private String resumo;

    /**
     * Vídeo de demonstração. Nulo enquanto não houver: a página do produto
     * mostra as capturas reais no lugar, em vez de um player vazio.
     */
    @Column(name = "video_url")
    private String videoUrl;

    /** Posição na vitrine. */
    @Column(nullable = false)
    @Builder.Default
    private int ordem = 0;

    /** Destino do handoff quando o assinante clica em "Abrir sistema". */
    @Column(name = "url_base", nullable = false)
    private String urlBase;

    @Column(nullable = false)
    @Builder.Default
    private boolean ativo = true;

    @Column(name = "criado_em", nullable = false)
    private Instant criadoEm;

    @PrePersist
    private void aoCriar() {
        if (criadoEm == null) criadoEm = Instant.now();
    }
}

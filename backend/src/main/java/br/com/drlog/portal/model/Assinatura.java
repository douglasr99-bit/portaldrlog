package br.com.drlog.portal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * O estado que decide se o assinante consegue abrir o sistema.
 *
 * É o registro que o handoff da etapa 2 consulta antes de emitir o token.
 */
@Entity
@Table(name = "assinaturas")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
@EqualsAndHashCode(of = "id")
public class Assinatura {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    /**
     * Duplicado a partir do plano para sustentar a unicidade
     * (tenant, produto). Mesma desnormalização deliberada que o Styllus faz
     * com tenant_id em servicos.
     */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "produto_id", nullable = false)
    private Produto produto;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "plano_id", nullable = false)
    private Plano plano;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EstadoAssinatura estado;

    /**
     * Até quando o acesso está liberado.
     *
     * Concentra a resposta num campo só para que a regra não seja recalculada,
     * de forma diferente, em cada lugar que precisar dela.
     */
    @Column(name = "acesso_ate")
    private Instant acessoAte;

    /**
     * De onde veio: 'administracao' ou 'autocadastro'.
     *
     * As duas origens merecem desconfianças diferentes: quem foi criado na
     * administração passou por uma conversa; quem se cadastrou sozinho, não.
     */
    @Column(nullable = false)
    @Builder.Default
    private String origem = "administracao";

    /** Qual gateway cobra esta assinatura. Nulo enquanto for cobrança manual. */
    private String gateway;

    /**
     * Identificadores do lado do gateway.
     *
     * Guardados para que uma segunda ativação não crie um segundo cliente ou
     * uma segunda assinatura lá — o que cobraria duas vezes pelo mesmo acesso.
     */
    @Column(name = "gateway_cliente_id")
    private String gatewayClienteId;

    @Column(name = "gateway_assinatura_id")
    private String gatewayAssinaturaId;

    /**
     * O checkout hospedado do Asaas, quando o teste foi iniciado com cartão.
     *
     * Guardado para reler o status no gateway em vez de acreditar no que vem
     * pela volta do navegador — a mesma desconfiança que o webhook já aplica.
     */
    @Column(name = "gateway_checkout_id")
    private String gatewayCheckoutId;

    /** Para o painel oferecer "terminar o cadastro" a quem fechou a aba. */
    @Column(name = "checkout_link")
    private String checkoutLink;

    /**
     * O cliente pediu para não renovar.
     *
     * Separado do estado de propósito: quem pagou até o dia 30 tem direito ao
     * dia 29. Cancelar interrompe a renovação, e o acesso termina sozinho
     * quando `acessoAte` passa.
     */
    @Column(name = "renovacao_cancelada", nullable = false)
    @Builder.Default
    private boolean renovacaoCancelada = false;

    /** Se a cobrança já é automática. */
    public boolean cobrancaAutomatica() {
        return gatewayAssinaturaId != null && !gatewayAssinaturaId.isBlank();
    }

    @Column(name = "iniciada_em", nullable = false)
    private Instant iniciadaEm;

    @Column(name = "cancelada_em")
    private Instant canceladaEm;

    @Column(name = "criada_em", nullable = false)
    private Instant criadaEm;

    @Column(name = "atualizada_em", nullable = false)
    private Instant atualizadaEm;

    /**
     * Se o sistema pode ser aberto agora.
     *
     * Exige as duas coisas: um estado que libere e uma data que não tenha
     * passado. O estado sozinho não basta — ele pode estar velho porque o
     * webhook falhou ou porque a rotina de vencimento não rodou; a data não
     * depende de ninguém ter reagido a tempo.
     */
    public boolean permiteAcesso() {
        if (estado == null || !estado.liberaAcesso()) return false;
        return acessoAte != null && acessoAte.isAfter(Instant.now());
    }

    @PrePersist
    private void aoCriar() {
        Instant agora = Instant.now();
        if (criadaEm == null) criadaEm = agora;
        if (iniciadaEm == null) iniciadaEm = agora;
        atualizadaEm = agora;
    }

    @PreUpdate
    private void aoAtualizar() {
        atualizadaEm = Instant.now();
    }
}

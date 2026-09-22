package br.com.drlog.portal.model;

/**
 * Estados possíveis de uma assinatura.
 *
 * A transição é dirigida pelos eventos de cobrança do gateway, não por ação
 * manual — ver seção 6.4 de directives/arquitetura_portal.md.
 */
public enum EstadoAssinatura {

    /** Em teste, sem cobrança ainda. */
    trial(true),

    /** Pagamento em dia. */
    ativa(true),

    /**
     * Vencida e não paga, dentro da carência.
     *
     * Continua liberando o acesso de propósito: uma loja de bairro atrasa o
     * boleto dois dias com frequência, e cortar o sistema que ela usa para
     * faturar é a pior forma de cobrar.
     */
    atrasada(true),

    /** Carência esgotada. */
    suspensa(false),

    /** Encerrada, por pedido do cliente ou inadimplência longa. */
    cancelada(false);

    private final boolean liberaAcesso;

    EstadoAssinatura(boolean liberaAcesso) {
        this.liberaAcesso = liberaAcesso;
    }

    /**
     * Se este estado, por si só, permite abrir o sistema.
     *
     * Não basta: o handoff também confere a data em `acesso_ate`. Um estado
     * `ativa` cujo acesso venceu e que ninguém atualizou não deve liberar
     * nada — o estado pode estar velho, a data não mente.
     */
    public boolean liberaAcesso() {
        return liberaAcesso;
    }
}

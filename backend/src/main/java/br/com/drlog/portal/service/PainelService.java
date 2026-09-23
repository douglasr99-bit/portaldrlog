package br.com.drlog.portal.service;

import br.com.drlog.portal.model.*;
import br.com.drlog.portal.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Monta o que o assinante vê ao entrar: seus assinantes, o que cada um já
 * contratou e o que ainda está disponível.
 */
@Service
public class PainelService {

    private final ContaRepository contas;
    private final ContaTenantRepository vinculos;
    private final AssinaturaRepository assinaturas;
    private final ProdutoRepository produtos;
    private final CobrancaService cobrancas;

    public PainelService(ContaRepository contas, ContaTenantRepository vinculos,
                         AssinaturaRepository assinaturas, ProdutoRepository produtos,
                         CobrancaService cobrancas) {
        this.contas = contas;
        this.vinculos = vinculos;
        this.assinaturas = assinaturas;
        this.produtos = produtos;
        this.cobrancas = cobrancas;
    }

    /** Uma linha do painel: o assinante e o que ele tem contratado. */
    public record LinhaTenant(Tenant tenant, Papel papel, List<Assinatura> assinaturas) {}

    /**
     * A fatura em aberto de cada assinatura, quando houver.
     *
     * A cobrança inteira, e não só o link: a tela precisa do vencimento para
     * dizer ao cliente em que dia o cartão será debitado. Usar a data de fim
     * de acesso no lugar dela erra por um dia, porque o acesso vai até o fim
     * do dia do débito — e data de cobrança errada na tela é o tipo de
     * detalhe que faz o cliente desconfiar da cobrança inteira.
     */
    public record Painel(List<LinhaTenant> linhas, List<Produto> disponiveis,
                         java.util.Map<java.util.UUID, Cobranca> faturas) {}

    @Transactional(readOnly = true)
    public Painel montar(UUID contaId) {
        Conta conta = contas.findById(contaId).orElseThrow();

        List<ContaTenant> meusVinculos = vinculos.findByContaOrderByCriadoEmAsc(conta);

        if (meusVinculos.isEmpty()) {
            return new Painel(List.of(), produtos.findByAtivoTrueOrderByOrdemAscNomeAsc(), Map.of());
        }

        List<Tenant> meusTenants = meusVinculos.stream().map(ContaTenant::getTenant).toList();

        // Uma consulta para todas as assinaturas, agrupadas em memória. Buscar
        // por tenant, em laço, faria N consultas para montar uma tela que
        // raramente passa de três linhas.
        Map<UUID, List<Assinatura>> porTenant = new HashMap<>();
        for (Assinatura a : assinaturas.findByTenantInOrderByCriadaEmAsc(meusTenants)) {
            porTenant.computeIfAbsent(a.getTenant().getId(), k -> new ArrayList<>()).add(a);
        }

        List<LinhaTenant> linhas = meusVinculos.stream()
                .map(v -> new LinhaTenant(
                        v.getTenant(),
                        v.getPapel(),
                        porTenant.getOrDefault(v.getTenant().getId(), List.of())))
                .toList();

        // O link da fatura em aberto de cada assinatura. Sem ele, o cliente
        // que acabou de assinar não teria como pagar sem sair da tela.
        Map<UUID, Cobranca> faturas = new HashMap<>();
        for (LinhaTenant linha : linhas)
            for (Assinatura a : linha.assinaturas())
                cobrancas.faturaEmAberto(a).ifPresent(c -> faturas.put(a.getId(), c));

        return new Painel(linhas, produtos.findByAtivoTrueOrderByOrdemAscNomeAsc(), faturas);
    }
}

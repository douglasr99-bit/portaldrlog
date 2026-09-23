package br.com.drlog.portal.service;

import br.com.drlog.portal.model.*;
import br.com.drlog.portal.repository.AssinaturaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * ============================================================================
 * LIBERAR O ACESSO
 *
 * O único lugar que liga o acesso e provisiona o WhatsApp.
 *
 * Existe porque agora há dois caminhos até o mesmo ponto — o teste com cartão
 * e a assinatura paga na hora — e mais tarde um terceiro, o webhook do
 * pagamento. Se cada um liberasse por conta própria, bastaria um esquecer de
 * provisionar para nascer um cliente pagante sem WhatsApp: o defeito que o
 * cliente percebe primeiro e reclama mais.
 * ============================================================================
 */
@Service
public class LiberacaoService {

    private static final Logger log = LoggerFactory.getLogger(LiberacaoService.class);

    private final AssinaturaRepository assinaturas;
    private final ProvisionamentoService provisionamento;

    public LiberacaoService(AssinaturaRepository assinaturas,
                            ProvisionamentoService provisionamento) {
        this.assinaturas = assinaturas;
        this.provisionamento = provisionamento;
    }

    /**
     * Liga o acesso até a data informada e garante o WhatsApp.
     *
     * Idempotente: chamar duas vezes não provisiona duas instâncias nem
     * encurta o acesso de quem já tem mais. Isso importa porque a confirmação
     * pode chegar duas vezes — uma pela volta do navegador e outra pelo
     * webhook —, e entrega repetida é parte do desenho do Asaas, não erro.
     */
    @Transactional
    public Assinatura liberar(Assinatura a, EstadoAssinatura estado, Instant acessoAte) {
        boolean eraBloqueada = a.getEstado() == EstadoAssinatura.aguardando_pagamento;

        a.setEstado(estado);
        // Nunca encurta: se o acesso já ia mais longe, quem chegou depois não
        // tira dias de quem já os tinha.
        if (a.getAcessoAte() == null || acessoAte.isAfter(a.getAcessoAte()))
            a.setAcessoAte(acessoAte);
        a.setCanceladaEm(null);
        assinaturas.save(a);

        if (eraBloqueada) {
            log.info("Acesso liberado para {} ({}) — {} até {}",
                     a.getTenant().getNome(), a.getTenant().getCodigo(), estado, a.getAcessoAte());
        }
        garantirWhatsApp(a);
        return a;
    }

    /**
     * O WhatsApp é o motivo pelo qual a loja compra.
     *
     * Falhar aqui não desfaz o acesso: o cliente já pagou ou já deixou o
     * cartão, e tirar o que ele contratou por causa de uma falha nossa na
     * Evolution seria punir a pessoa errada. A administração mostra o
     * provisionamento pendente, e ele é refeito de lá.
     */
    private void garantirWhatsApp(Assinatura a) {
        if (provisionamento.de(a.getTenant(), a.getProduto()).isPresent()) return;
        try {
            provisionamento.provisionar(a.getTenant(), a.getProduto());
        } catch (Exception e) {
            log.warn("Acesso de {} liberado, mas o WhatsApp não foi provisionado: {}",
                     a.getTenant().getNome(), e.toString());
        }
    }
}

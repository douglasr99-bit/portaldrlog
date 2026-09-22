package br.com.drlog.portal.web;

import br.com.drlog.portal.model.*;
import br.com.drlog.portal.repository.*;
import br.com.drlog.portal.service.ContaAutenticada;
import br.com.drlog.portal.service.EmissorDeToken;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

/**
 * ============================================================================
 * ABRIR UM SISTEMA
 *
 * O ponto em que a assinatura vira acesso. Confere, emite o token e entrega
 * uma página que o envia ao sistema por POST.
 *
 * POST, e não GET com ?token=: query string entra no log de acesso do proxy,
 * no histórico do navegador e no cabeçalho Referer de qualquer requisição
 * seguinte. O corpo de um POST não vai para nenhum dos três.
 * ============================================================================
 */
@Controller
public class AberturaController {

    private final AssinaturaRepository assinaturas;
    private final ContaRepository contas;
    private final ContaTenantRepository vinculos;
    private final EmissorDeToken emissor;

    public AberturaController(AssinaturaRepository assinaturas, ContaRepository contas,
                              ContaTenantRepository vinculos, EmissorDeToken emissor) {
        this.assinaturas = assinaturas;
        this.contas = contas;
        this.vinculos = vinculos;
        this.emissor = emissor;
    }

    /**
     * A rota é POST porque emitir token tem efeito: consome um jti e é
     * registrável. Em GET, um <img src> numa página qualquer dispararia a
     * emissão sem o usuário saber.
     */
    @PostMapping("/abrir/{assinaturaId}")
    @Transactional(readOnly = true)
    public String abrir(@PathVariable UUID assinaturaId,
                        @AuthenticationPrincipal ContaAutenticada autenticada,
                        Model model, RedirectAttributes redirect) {

        Assinatura assinatura = assinaturas.findById(assinaturaId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        Conta conta = contas.findById(autenticada.getId()).orElseThrow();

        // Estar autenticado não basta: é preciso estar vinculado a ESTE
        // assinante. Sem esta conferência, quem descobrisse o id de uma
        // assinatura alheia abriria o sistema de outra loja.
        ContaTenant vinculo = vinculos.findByContaAndTenant(conta, assinatura.getTenant())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        // A decisão de acesso é da assinatura, e é aqui que ela acontece. O
        // sistema vendido confia no token justamente porque esta linha rodou.
        if (!assinatura.permiteAcesso()) {
            redirect.addFlashAttribute("erro",
                    "O acesso a %s está bloqueado. Regularize a assinatura para voltar a usar."
                            .formatted(assinatura.getProduto().getNome()));
            return "redirect:/painel";
        }

        model.addAttribute("destino", assinatura.getProduto().getUrlBase().replaceAll("/+$", "") + "/sso");
        model.addAttribute("token", emissor.emitir(assinatura, conta, vinculo.getPapel()));
        model.addAttribute("produto", assinatura.getProduto().getNome());
        return "abrir";
    }
}

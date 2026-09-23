package br.com.drlog.portal.web;

import br.com.drlog.portal.service.ContaAutenticada;
import br.com.drlog.portal.service.PainelService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PainelController {

    private final PainelService painel;
    private final br.com.drlog.portal.service.CobrancaService cobranca;
    private final br.com.drlog.portal.repository.ContaTenantRepository vinculos;
    private final br.com.drlog.portal.repository.ContaRepository contas;
    private final br.com.drlog.portal.repository.AssinaturaRepository assinaturas;

    public PainelController(PainelService painel,
                            br.com.drlog.portal.service.CobrancaService cobranca,
                            br.com.drlog.portal.repository.ContaTenantRepository vinculos,
                            br.com.drlog.portal.repository.ContaRepository contas,
                            br.com.drlog.portal.repository.AssinaturaRepository assinaturas) {
        this.painel = painel;
        this.cobranca = cobranca;
        this.vinculos = vinculos;
        this.contas = contas;
        this.assinaturas = assinaturas;
    }

    /**
     * O cliente converte o teste em assinatura, sozinho.
     *
     * Confere o vínculo antes de tudo: sem isso, quem descobrisse o id de uma
     * assinatura alheia mandaria cobrança para outra loja.
     */
    @org.springframework.web.bind.annotation.PostMapping("/painel/assinar/{id}")
    public String assinar(@org.springframework.web.bind.annotation.PathVariable java.util.UUID id,
                          @org.springframework.web.bind.annotation.RequestParam(required = false) String documento,
                          @org.springframework.security.core.annotation.AuthenticationPrincipal ContaAutenticada autenticada,
                          org.springframework.web.servlet.mvc.support.RedirectAttributes redirect) {
        try {
            var assinatura = assinaturas.findById(id)
                    .orElseThrow(() -> new br.com.drlog.portal.service.AdminService.Recusa("Assinatura não encontrada."));
            var conta = contas.findById(autenticada.getId()).orElseThrow();
            vinculos.findByContaAndTenant(conta, assinatura.getTenant())
                    .orElseThrow(() -> new br.com.drlog.portal.service.AdminService.Recusa("Assinatura não encontrada."));

            if (documento == null || documento.replaceAll("\\D", "").length() < 11)
                throw new br.com.drlog.portal.service.AdminService.Recusa(
                    "Informe um CNPJ ou CPF válido — é exigido para emitir a cobrança.");

            cobranca.assinar(id, documento);
            redirect.addFlashAttribute("boasVindas",
                    "Assinatura criada. O link para pagar está aí embaixo — "
                  + "o acesso continua liberado enquanto isso.");
        } catch (br.com.drlog.portal.service.AdminService.Recusa e) {
            redirect.addFlashAttribute("erro", e.getMessage());
        } catch (Exception e) {
            redirect.addFlashAttribute("erro", "Não foi possível criar a cobrança agora. Tente de novo em instantes.");
        }
        return "redirect:/painel";
    }

    @GetMapping("/painel")
    public String painel(@AuthenticationPrincipal ContaAutenticada conta, Model model) {
        PainelService.Painel dados = painel.montar(conta.getId());
        model.addAttribute("conta", conta);
        model.addAttribute("linhas", dados.linhas());
        model.addAttribute("disponiveis", dados.disponiveis());
        model.addAttribute("faturas", dados.faturas());
        return "painel";
    }
}

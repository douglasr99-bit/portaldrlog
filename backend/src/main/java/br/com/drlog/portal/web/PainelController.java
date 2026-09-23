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
            minhaAssinatura(id, autenticada);

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

    /**
     * O cliente cancela sozinho.
     *
     * Existe porque cobrança automática sem botão de cancelar é armadilha, não
     * assinatura: quem deixou o cartão precisa conseguir parar pelo mesmo
     * caminho por onde começou, sem depender de alguém responder no WhatsApp.
     *
     * O acesso não é cortado aqui — quem pagou até o dia 30 tem direito ao dia
     * 29. Cancelar interrompe a renovação, e a data faz o resto sozinha.
     */
    @org.springframework.web.bind.annotation.PostMapping("/painel/cancelar/{id}")
    public String cancelar(@org.springframework.web.bind.annotation.PathVariable java.util.UUID id,
                           @AuthenticationPrincipal ContaAutenticada autenticada,
                           org.springframework.web.servlet.mvc.support.RedirectAttributes redirect) {
        try {
            minhaAssinatura(id, autenticada);
            var cancelada = cobranca.cancelarRenovacao(id);
            redirect.addFlashAttribute("boasVindas", cancelada.getAcessoAte() == null
                    ? "Cadastro cancelado. Você não será cobrado."
                    : "Renovação cancelada. Você não será cobrado de novo, e o sistema "
                    + "continua funcionando até " + emDia(cancelada.getAcessoAte()) + ".");
        } catch (br.com.drlog.portal.service.AdminService.Recusa e) {
            redirect.addFlashAttribute("erro", e.getMessage());
        } catch (Exception e) {
            redirect.addFlashAttribute("erro",
                    "Não foi possível cancelar agora. Tente de novo em instantes.");
        }
        return "redirect:/painel";
    }

    /**
     * A assinatura, se ela for mesmo de quem está pedindo.
     *
     * Sem esta conferência, quem descobrisse o id de uma assinatura alheia
     * cancelaria a assinatura de outra loja — ou mandaria cobrança para ela.
     */
    private br.com.drlog.portal.model.Assinatura minhaAssinatura(
            java.util.UUID id, ContaAutenticada autenticada) {
        var assinatura = assinaturas.findById(id)
                .orElseThrow(() -> new br.com.drlog.portal.service.AdminService.Recusa("Assinatura não encontrada."));
        var conta = contas.findById(autenticada.getId()).orElseThrow();
        vinculos.findByContaAndTenant(conta, assinatura.getTenant())
                .orElseThrow(() -> new br.com.drlog.portal.service.AdminService.Recusa("Assinatura não encontrada."));
        return assinatura;
    }

    private static String emDia(java.time.Instant i) {
        return java.time.LocalDate.ofInstant(i, java.time.ZoneId.of("America/Sao_Paulo"))
                .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
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

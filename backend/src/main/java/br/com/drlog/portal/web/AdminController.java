package br.com.drlog.portal.web;

import br.com.drlog.portal.model.EstadoAssinatura;
import br.com.drlog.portal.repository.ProdutoRepository;
import br.com.drlog.portal.repository.TenantRepository;
import br.com.drlog.portal.service.AdminService;
import br.com.drlog.portal.service.CobrancaService;
import br.com.drlog.portal.service.ProvisionamentoService;
import br.com.drlog.portal.service.ContaAutenticada;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

/**
 * Tela de administração da plataforma.
 *
 * Protegida por ROLE_ADMIN no SecurityConfig — não basta estar autenticado.
 */
@Controller
@RequestMapping("/admin")
public class AdminController {

    private final AdminService admin;
    private final ProvisionamentoService provisionamento;
    private final CobrancaService cobranca;
    private final TenantRepository tenants;
    private final ProdutoRepository produtos;
    private final br.com.drlog.portal.service.MedicaoService medicao;

    public AdminController(AdminService admin, ProvisionamentoService provisionamento,
                           CobrancaService cobranca,
                           TenantRepository tenants, ProdutoRepository produtos,
                           br.com.drlog.portal.service.MedicaoService medicao) {
        this.admin = admin;
        this.provisionamento = provisionamento;
        this.cobranca = cobranca;
        this.tenants = tenants;
        this.produtos = produtos;
        this.medicao = medicao;
    }

    @GetMapping
    public String lista(@AuthenticationPrincipal ContaAutenticada conta, Model model) {
        model.addAttribute("conta", conta);
        model.addAttribute("linhas", admin.listar());
        model.addAttribute("planos", admin.planosDisponiveis());
        model.addAttribute("provisionamentos", admin.provisionamentosPorTenant());
        model.addAttribute("evolutionConfigurada", provisionamento.configurado());
        model.addAttribute("asaasConfigurado", cobranca.configurado());
        model.addAttribute("funil", medicao.temDados() ? medicao.diasComMovimento(14) : null);
        model.addAttribute("origens", medicao.temDados() ? medicao.principais(14, 8) : null);
        return "admin/lista";
    }

    @GetMapping("/novo")
    public String novo(@AuthenticationPrincipal ContaAutenticada conta, Model model) {
        model.addAttribute("conta", conta);
        model.addAttribute("planos", admin.planosDisponiveis());
        return "admin/novo";
    }

    @PostMapping("/assinantes")
    public String criar(@RequestParam String nomeLoja,
                        @RequestParam(required = false) String documento,
                        @RequestParam(required = false) String codigo,
                        @RequestParam UUID planoId,
                        @RequestParam String email,
                        @RequestParam String nomeResponsavel,
                        @RequestParam String senha,
                        RedirectAttributes redirect) {
        try {
            var tenant = admin.criarAssinante(nomeLoja, documento, codigo, planoId,
                                              email, nomeResponsavel, senha);
            redirect.addFlashAttribute("sucesso",
                    "Assinante \"%s\" criado, com acesso para %s.".formatted(tenant.getNome(), email));
            return "redirect:/admin";
        } catch (AdminService.Recusa e) {
            // Os dados digitados voltam para a tela: obrigar a redigitar tudo
            // por causa de um e-mail repetido é o tipo de atrito que faz
            // alguém preferir o psql.
            redirect.addFlashAttribute("erro", e.getMessage());
            redirect.addFlashAttribute("nomeLoja", nomeLoja);
            redirect.addFlashAttribute("documento", documento);
            redirect.addFlashAttribute("codigo", codigo);
            redirect.addFlashAttribute("email", email);
            redirect.addFlashAttribute("nomeResponsavel", nomeResponsavel);
            redirect.addFlashAttribute("planoId", planoId);
            return "redirect:/admin/novo";
        }
    }

    @PostMapping("/assinantes/{tenantId}/contratar")
    public String contratar(@PathVariable UUID tenantId, @RequestParam UUID planoId,
                            RedirectAttributes redirect) {
        try {
            var a = admin.contratar(tenantId, planoId);
            redirect.addFlashAttribute("sucesso",
                    "%s agora assina %s.".formatted(a.getTenant().getNome(), a.getProduto().getNome()));
        } catch (AdminService.Recusa e) {
            redirect.addFlashAttribute("erro", e.getMessage());
        }
        return "redirect:/admin";
    }

    @PostMapping("/assinaturas/{id}/whatsapp")
    public String provisionarWhatsApp(@PathVariable UUID id, RedirectAttributes redirect) {
        try {
            var assinatura = admin.assinatura(id);
            var p = provisionamento.provisionar(assinatura.getTenant(), assinatura.getProduto());
            if ("ativo".equals(p.getEstado())) {
                redirect.addFlashAttribute("sucesso",
                        "Instância \"%s\" pronta para %s.".formatted(p.getInstancia(),
                                assinatura.getTenant().getNome()));
            } else {
                redirect.addFlashAttribute("erro",
                        "Não foi possível provisionar: %s".formatted(p.getErro()));
            }
        } catch (AdminService.Recusa e) {
            redirect.addFlashAttribute("erro", e.getMessage());
        }
        return "redirect:/admin";
    }

    @PostMapping("/assinaturas/{id}/cobranca")
    public String ativarCobranca(@PathVariable UUID id, RedirectAttributes redirect) {
        try {
            var a = cobranca.ativarCobranca(id);
            redirect.addFlashAttribute("sucesso",
                    "Cobrança automática ativada para %s. O Asaas emite a próxima em %s."
                            .formatted(a.getTenant().getNome(),
                                    a.getAcessoAte() == null ? "breve"
                                        : a.getAcessoAte().atZone(java.time.ZoneId.of("America/Sao_Paulo")).toLocalDate()));
        } catch (AdminService.Recusa e) {
            redirect.addFlashAttribute("erro", e.getMessage());
        } catch (Exception e) {
            redirect.addFlashAttribute("erro", "O Asaas recusou: " + e.getMessage());
        }
        return "redirect:/admin";
    }

    @PostMapping("/assinaturas/{id}/renovar")
    public String renovar(@PathVariable UUID id, RedirectAttributes redirect) {
        try {
            var a = admin.renovar(id);
            redirect.addFlashAttribute("sucesso",
                    "%s renovado — acesso até %s.".formatted(
                            a.getTenant().getNome(),
                            a.getAcessoAte().atZone(java.time.ZoneId.of("America/Sao_Paulo"))
                             .toLocalDate()));
        } catch (AdminService.Recusa e) {
            redirect.addFlashAttribute("erro", e.getMessage());
        }
        return "redirect:/admin";
    }

    @PostMapping("/assinaturas/{id}/estado")
    public String estado(@PathVariable UUID id, @RequestParam EstadoAssinatura estado,
                         RedirectAttributes redirect) {
        try {
            var a = admin.alterarEstado(id, estado);
            redirect.addFlashAttribute("sucesso",
                    "%s agora está \"%s\".".formatted(a.getTenant().getNome(), estado));
        } catch (AdminService.Recusa e) {
            redirect.addFlashAttribute("erro", e.getMessage());
        }
        return "redirect:/admin";
    }
}

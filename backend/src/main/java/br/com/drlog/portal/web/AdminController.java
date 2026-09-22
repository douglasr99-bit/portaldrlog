package br.com.drlog.portal.web;

import br.com.drlog.portal.model.EstadoAssinatura;
import br.com.drlog.portal.service.AdminService;
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

    public AdminController(AdminService admin) {
        this.admin = admin;
    }

    @GetMapping
    public String lista(@AuthenticationPrincipal ContaAutenticada conta, Model model) {
        model.addAttribute("conta", conta);
        model.addAttribute("linhas", admin.listar());
        model.addAttribute("planos", admin.planosDisponiveis());
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

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

    public PainelController(PainelService painel) {
        this.painel = painel;
    }

    @GetMapping("/painel")
    public String painel(@AuthenticationPrincipal ContaAutenticada conta, Model model) {
        PainelService.Painel dados = painel.montar(conta.getId());
        model.addAttribute("conta", conta);
        model.addAttribute("linhas", dados.linhas());
        model.addAttribute("disponiveis", dados.disponiveis());
        return "painel";
    }
}

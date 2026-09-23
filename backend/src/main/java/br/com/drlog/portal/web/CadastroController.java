package br.com.drlog.portal.web;

import br.com.drlog.portal.config.Empresa;
import br.com.drlog.portal.model.Produto;
import br.com.drlog.portal.repository.PlanoRepository;
import br.com.drlog.portal.repository.ProdutoRepository;
import br.com.drlog.portal.service.AdminService;
import br.com.drlog.portal.service.CadastroService;
import br.com.drlog.portal.service.ContaAutenticada;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

/**
 * ============================================================================
 * ASSINAR PELA VITRINE
 *
 * O visitante cria a conta, ganha o período de teste e entra — sem ninguém
 * do outro lado.
 * ============================================================================
 */
@Controller
public class CadastroController {

    private final CadastroService cadastro;
    private final ProdutoRepository produtos;
    private final PlanoRepository planos;
    private final Empresa empresa;

    private final SecurityContextRepository sessao = new HttpSessionSecurityContextRepository();

    public CadastroController(CadastroService cadastro, ProdutoRepository produtos,
                              PlanoRepository planos, Empresa empresa) {
        this.cadastro = cadastro;
        this.produtos = produtos;
        this.planos = planos;
        this.empresa = empresa;
    }

    @GetMapping("/assinar/{codigo}")
    public String formulario(@PathVariable String codigo, Model model) {
        Produto produto = produtos.findByCodigo(codigo).filter(Produto::isAtivo)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        model.addAttribute("produto", produto);
        model.addAttribute("planos", planos.findByProdutoAndAtivoTrueOrderByPrecoCentavosAsc(produto));
        model.addAttribute("diasDeTeste", cadastro.diasDeTeste());
        model.addAttribute("empresa", empresa);
        return "site/assinar";
    }

    @PostMapping("/assinar/{codigo}")
    public String criar(@PathVariable String codigo,
                        @RequestParam String nomeLoja,
                        @RequestParam UUID planoId,
                        @RequestParam String nomeResponsavel,
                        @RequestParam String email,
                        @RequestParam String senha,
                        HttpServletRequest requisicao, HttpServletResponse resposta,
                        RedirectAttributes redirect) {
        try {
            var r = cadastro.cadastrar(nomeLoja, planoId, nomeResponsavel, email, senha,
                                       enderecoDe(requisicao));
            entrar(r.conta(), requisicao, resposta);
            redirect.addFlashAttribute("boasVindas",
                    "Pronto, %s! Seu teste de %d dias começou.".formatted(
                            r.conta().getNome().split(" ")[0], cadastro.diasDeTeste()));
            return "redirect:/painel";

        } catch (AdminService.Recusa e) {
            // Os dados digitados voltam para a tela. Obrigar a redigitar tudo
            // por causa de uma senha curta é atrito na hora em que a pessoa
            // está mais perto de desistir.
            redirect.addFlashAttribute("erro", e.getMessage());
            redirect.addFlashAttribute("nomeLoja", nomeLoja);
            redirect.addFlashAttribute("nomeResponsavel", nomeResponsavel);
            redirect.addFlashAttribute("email", email);
            redirect.addFlashAttribute("planoId", planoId);
            return "redirect:/assinar/" + codigo;
        }
    }

    /**
     * Abre a sessão da conta recém-criada.
     *
     * Sem isso, a pessoa terminaria o cadastro numa tela de login pedindo a
     * senha que ela acabou de escolher — o pior momento possível para pedir
     * qualquer coisa.
     */
    private void entrar(br.com.drlog.portal.model.Conta conta,
                        HttpServletRequest requisicao, HttpServletResponse resposta) {
        ContaAutenticada autenticada = new ContaAutenticada(conta);
        Authentication auth = UsernamePasswordAuthenticationToken.authenticated(
                autenticada, null, autenticada.getAuthorities());

        HttpSession anterior = requisicao.getSession(false);
        if (anterior != null) anterior.invalidate();
        requisicao.getSession(true);

        SecurityContext contexto = SecurityContextHolder.createEmptyContext();
        contexto.setAuthentication(auth);
        SecurityContextHolder.setContext(contexto);
        sessao.saveContext(contexto, requisicao, resposta);
    }

    /**
     * O endereço de quem chamou, atravessando o proxy.
     *
     * Sem ler o X-Forwarded-For, todo cadastro pareceria vir do Traefik — e a
     * trava por endereço barraria o segundo cliente legítimo do dia.
     */
    private String enderecoDe(HttpServletRequest r) {
        String encaminhado = r.getHeader("X-Forwarded-For");
        if (encaminhado != null && !encaminhado.isBlank())
            return encaminhado.split(",")[0].trim();
        return r.getRemoteAddr();
    }
}

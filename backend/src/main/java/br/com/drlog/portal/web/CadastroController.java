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

import java.time.ZoneId;
import java.util.UUID;

/**
 * ============================================================================
 * ASSINAR PELA VITRINE
 *
 * Dois caminhos: testar grátis deixando um cartão, ou assinar na hora e pagar
 * como puder. O cartão só é exigido no teste — é o preço da palavra "grátis",
 * não uma preferência de recebimento.
 *
 * Em nenhum dos dois o acesso é liberado aqui. A conta nasce bloqueada e só
 * abre quando o Asaas confirma, porque é o cliente que volta da tela do
 * gateway, e o que ele traz na URL não prova nada.
 * ============================================================================
 */
@Controller
public class CadastroController {

    private final CadastroService cadastro;
    private final ProdutoRepository produtos;
    private final PlanoRepository planos;
    private final Empresa empresa;
    private final br.com.drlog.portal.service.CobrancaService cobranca;

    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");

    private final SecurityContextRepository sessao = new HttpSessionSecurityContextRepository();

    public CadastroController(CadastroService cadastro, ProdutoRepository produtos,
                              PlanoRepository planos, Empresa empresa,
                              br.com.drlog.portal.service.CobrancaService cobranca) {
        this.cadastro = cadastro;
        this.produtos = produtos;
        this.planos = planos;
        this.empresa = empresa;
        this.cobranca = cobranca;
    }

    @GetMapping("/assinar/{codigo}")
    public String formulario(@PathVariable String codigo, Model model) {
        Produto produto = produtos.findByCodigo(codigo).filter(Produto::isAtivo)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        model.addAttribute("produto", produto);
        model.addAttribute("planos", planos.findByProdutoAndAtivoTrueOrderByPrecoCentavosAsc(produto));
        model.addAttribute("diasDeTeste", cadastro.diasDeTeste());
        model.addAttribute("primeiraCobranca", java.time.LocalDate.now(FUSO)
                .plusDays(cadastro.diasDeTeste())
                .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")));
        // Sem chave do Asaas não há como validar cartão nem emitir cobrança:
        // os dois caminhos terminam lá. Oferecer um formulário que quebraria
        // na tela seguinte é pior que dizer que está indisponível.
        model.addAttribute("testeDisponivel", cobranca.configurado());
        model.addAttribute("cadastroDisponivel", cobranca.configurado());
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
                        @RequestParam(defaultValue = "TESTE") String modo,
                        @RequestParam(required = false) String documento,
                        HttpServletRequest requisicao, HttpServletResponse resposta,
                        RedirectAttributes redirect) {

        CadastroService.Modo escolha = "AGORA".equalsIgnoreCase(modo)
                ? CadastroService.Modo.AGORA : CadastroService.Modo.TESTE;
        try {
            // Antes de criar qualquer coisa. Os dois caminhos terminam no
            // Asaas agora, e sem chave os dois falham — mas a conta já teria
            // sido criada, prendendo o e-mail da pessoa numa conta que ela não
            // consegue usar nem recadastrar.
            if (!cobranca.configurado())
                throw new AdminService.Recusa(
                    "O cadastro pelo site está indisponível no momento. "
                  + "Fale com a gente no WhatsApp que criamos o seu acesso.");

            var r = cadastro.cadastrar(nomeLoja, planoId, nomeResponsavel, email, senha,
                                       escolha, documento, enderecoDe(requisicao));

            // A sessão é aberta antes de mandar para o Asaas: quem voltar do
            // checkout cai direto no painel, e quem desistir no meio consegue
            // retomar de lá em vez de recomeçar o cadastro.
            entrar(r.conta(), requisicao, resposta);

            if (escolha == CadastroService.Modo.TESTE)
                return "redirect:" + cobranca.iniciarTesteComCartao(r.assinatura());

            cobranca.assinar(r.assinatura().getId(), documento);
            redirect.addFlashAttribute("boasVindas",
                    ("Conta criada, %s. Pague a primeira fatura aí embaixo e o sistema abre — "
                   + "no Pix é na hora; no boleto, quando compensar.")
                            .formatted(r.conta().getNome().split(" ")[0]));
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
            redirect.addFlashAttribute("documento", documento);
            redirect.addFlashAttribute("modo", escolha.name());
            return "redirect:/assinar/" + codigo;
        }
    }

    /**
     * A volta do checkout do Asaas.
     *
     * Serve para os três desfechos — pago, cancelado e expirado — porque o que
     * decide não é por qual endereço o cliente voltou, e sim o status lido no
     * gateway. Quem editasse a URL de sucesso não ganharia nada.
     *
     * O webhook faz o mesmo trabalho para quem fecha a aba antes de voltar; as
     * duas entradas são seguras porque a liberação é idempotente.
     */
    @GetMapping("/assinar/retorno/{assinaturaId}")
    public String retorno(@PathVariable UUID assinaturaId, RedirectAttributes redirect) {
        try {
            if (cobranca.confirmarCheckout(assinaturaId))
                redirect.addFlashAttribute("boasVindas",
                        ("Cartão aprovado. Seu teste de %d dias começou — "
                       + "o sistema já está liberado.").formatted(cadastro.diasDeTeste()));
            else
                redirect.addFlashAttribute("erro",
                        "O cadastro do cartão não foi concluído, então o teste ainda não começou. "
                      + "Dá para terminar aí embaixo.");
        } catch (Exception e) {
            redirect.addFlashAttribute("erro",
                    "Não consegui confirmar seu cartão agora. Tente terminar o cadastro aí embaixo.");
        }
        return "redirect:/painel";
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

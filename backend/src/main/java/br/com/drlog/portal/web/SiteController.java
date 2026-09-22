package br.com.drlog.portal.web;

import br.com.drlog.portal.config.Empresa;
import br.com.drlog.portal.model.Plano;
import br.com.drlog.portal.model.Produto;
import br.com.drlog.portal.repository.PlanoRepository;
import br.com.drlog.portal.repository.ProdutoRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * A vitrine — as páginas que quem ainda não é assinante vê.
 *
 * Fica separada do PainelController de propósito: são públicas, não têm
 * sessão e o objetivo delas é outro. Misturar as duas coisas no mesmo
 * controller é o caminho para uma rota de venda acabar exigindo login, ou
 * pior, uma rota do painel acabar pública.
 */
@Controller
public class SiteController {

    private final ProdutoRepository produtos;
    private final PlanoRepository planos;
    private final Empresa empresa;

    /**
     * Como a abertura apresenta as telas: "carrossel" (troca sozinho) ou
     * "fixa" (uma só, parada).
     *
     * É configuração, e não parâmetro de URL: um ?abertura=... criaria
     * endereços diferentes com o mesmo conteúdo, que buscador trata como
     * página duplicada.
     */
    @Value("${app.vitrine.abertura:carrossel}")
    private String abertura;

    /**
     * Quantas vagas "em breve" entram no carrossel depois dos sistemas reais.
     *
     * Existem para ver o carrossel funcionando com mais de um sistema enquanto
     * só há um no ar. Devem ir a zero quando o segundo produto existir — uma
     * vitrine com mais vaga vazia que sistema diz mais sobre o que falta do
     * que sobre o que há.
     */
    @Value("${app.vitrine.vagas-em-breve:2}")
    private int vagasEmBreve;

    public SiteController(ProdutoRepository produtos, PlanoRepository planos, Empresa empresa) {
        this.produtos = produtos;
        this.planos = planos;
        this.empresa = empresa;
    }

    @GetMapping("/")
    public String inicio(Model model) {
        List<Produto> catalogo = produtos.findByAtivoTrueOrderByOrdemAscNomeAsc();

        // O preço precisa aparecer já na listagem: esconder atrás de um clique
        // é o que faz o visitante presumir que é caro e fechar a aba.
        model.addAttribute("catalogo", catalogo.stream()
                .map(p -> new ItemVitrine(p, menorPlano(p)))
                .toList());
        model.addAttribute("slides", slidesDe(catalogo));
        model.addAttribute("abertura", abertura);
        model.addAttribute("empresa", empresa);
        return "site/inicio";
    }

    @GetMapping("/sistemas/{codigo}")
    public String produto(@PathVariable String codigo, Model model) {
        Produto produto = produtos.findByCodigo(codigo)
                .filter(Produto::isAtivo)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        model.addAttribute("produto", produto);
        model.addAttribute("planos", planos.findByProdutoAndAtivoTrueOrderByPrecoCentavosAsc(produto));
        model.addAttribute("empresa", empresa);
        return "site/produto";
    }

    /** Produto com o plano mais barato já resolvido, para a listagem. */
    public record ItemVitrine(Produto produto, Plano plano) {}

    /**
     * Uma captura dentro da galeria de um sistema.
     *
     * @param arquivo nome em /img, sem extensão, para telas largas
     * @param celular nome em /img, sem extensão, para telas estreitas
     */
    public record Imagem(String arquivo, String celular, String rotulo, String legenda, String alt) {}

    /**
     * Um sistema na abertura. Ocupa o envelope inteiro, e os sistemas se
     * alternam entre si.
     *
     * @param emBreve slide reservado, sem produto por trás — ver vagasEmBreve
     */
    public record SlideSistema(String codigo, String titulo, String resumo,
                               Plano plano, List<Imagem> imagens, boolean emBreve,
                               String carimbo, String chamada, String destaque, String texto) {}

    /**
     * Os slides da abertura: um por sistema no ar, seguidos das vagas
     * reservadas.
     */
    private List<SlideSistema> slidesDe(List<Produto> catalogo) {
        List<SlideSistema> slides = new ArrayList<>();

        for (Produto p : catalogo) {
            slides.add(new SlideSistema(
                    p.getCodigo(), p.getNome(), p.getResumo(),
                    menorPlano(p), imagensDe(p.getCodigo()), false,
                    "Em produção desde agosto de 2026",
                    "O cliente deixa. Você conserta.", "O sistema avisa.",
                    "Sapataria, lavanderia, conserto de bicicleta, assistência técnica — "
                  + "personalizamos o software para a realidade do seu negócio. "
                  + "esse é o trabalho da Drlog."));
        }

        // Vagas "em breve". Existem para que o carrossel possa ser visto
        // funcionando com mais de um sistema antes de o segundo existir.
        //
        // Não carregam nome nem descrição de produto: anunciar um sistema que
        // ainda não existe é promessa, e promessa numa página de venda é o
        // primeiro passo para a vitrine deixar de ser confiável.
        for (int i = 0; i < vagasEmBreve; i++) {
            slides.add(new SlideSistema("em-breve-" + (i + 1), "Em breve", null,
                    null, List.of(), true,
                    "Em construção",
                    "O próximo sistema", "está sendo feito.",
                    "Ele vai nascer do mesmo jeito que o Styllus: de um problema concreto "
                  + "de uma loja concreta — e não de uma lista de funcionalidades escrita "
                  + "antes de alguém precisar dela."));
        }

        return slides;
    }

    /**
     * As capturas de cada sistema.
     *
     * Específicas do Styllus e escritas aqui pelo mesmo motivo que o conteúdo
     * da página do produto está no template: com um produto, tabela seria
     * estrutura sem uso. Ao chegar o terceiro, isto vira dado.
     */
    private List<Imagem> imagensDe(String codigo) {
        if (!"styllos".equals(codigo)) return List.of();
        return List.of(
            new Imagem("styllus-kanban", "styllus-kanban-celular", "Operação",
                "O quadro da bancada: fila, mesa do dia e prontos.",
                "Painel de operação com as colunas Mesa do Dia, Fila de Serviços e Realizados."),
            new Imagem("styllus-resumo", "styllus-resumo-celular", "Resumo do mês",
                "Faturamento, ticket médio e o que ainda está a receber na entrega.",
                "Resumo de produção com faturamento do mês e ranking de clientes."),
            new Imagem("styllus-cadastro", "styllus-cadastro-celular", "Novo serviço",
                "Cliente, o que será feito, valor e forma de pagamento.",
                "Formulário de cadastro de um novo serviço.")
        );
    }

    private Plano menorPlano(Produto p) {
        return planos.findByProdutoAndAtivoTrueOrderByPrecoCentavosAsc(p)
                .stream().findFirst().orElse(null);
    }
}

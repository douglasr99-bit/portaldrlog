package br.com.drlog.portal.service;

import br.com.drlog.portal.model.*;
import br.com.drlog.portal.repository.ProvisionamentoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ============================================================================
 * PROVISIONAMENTO DA INSTÂNCIA DE WHATSAPP
 *
 * O Portal é o único que fala com a Evolution usando a chave global. Cada
 * sistema vendido recebe apenas o token da instância do seu assinante.
 *
 * A diferença aparece quando o roteamento de tenant erra: com token por
 * instância, a Evolution responde 401 e a mensagem não sai. Com a chave
 * global espalhada pelos sistemas, ela sai — pelo WhatsApp da loja errada,
 * para o cliente da loja errada, e isso não tem desfazer.
 * ============================================================================
 */
@Service
public class ProvisionamentoService {

    private static final Logger log = LoggerFactory.getLogger(ProvisionamentoService.class);

    private final ProvisionamentoRepository provisionamentos;
    private final RestClient http;
    private final String chaveGlobal;
    private final String prefixo;

    public ProvisionamentoService(ProvisionamentoRepository provisionamentos,
                                  @Value("${app.evolution.base-url:https://api.drlog.com.br}") String baseUrl,
                                  @Value("${app.evolution.chave:}") String chaveGlobal,
                                  @Value("${app.evolution.prefixo-instancia:loja_}") String prefixo) {
        this.provisionamentos = provisionamentos;
        this.chaveGlobal = chaveGlobal;
        this.prefixo = prefixo;
        // RestClient, do próprio spring-web: síncrono, que é o que este caso
        // pede, e sem arrastar o WebFlux inteiro para dentro do Portal.
        this.http = RestClient.builder().baseUrl(baseUrl.replaceAll("/+$", "")).build();
    }

    public boolean configurado() { return chaveGlobal != null && !chaveGlobal.isBlank(); }

    @Transactional(readOnly = true)
    public Optional<Provisionamento> de(Tenant tenant, Produto produto) {
        return provisionamentos.findByTenantAndProduto(tenant, produto);
    }

    @Transactional(readOnly = true)
    public List<Provisionamento> listar() { return provisionamentos.findAllByOrderByCriadoEmDesc(); }

    /**
     * Cria a instância na Evolution e guarda a credencial dela.
     *
     * Idempotente pelo par (assinante, produto): chamar de novo com a
     * instância já ativa não cria outra. Uma segunda instância para a mesma
     * loja significaria um segundo número de WhatsApp — e o cliente dela
     * recebendo aviso de um número que não conhece.
     */
    @Transactional
    public Provisionamento provisionar(Tenant tenant, Produto produto) {
        if (!configurado()) {
            throw new AdminService.Recusa(
                "A chave da Evolution não está configurada no Portal (APP_EVOLUTION_CHAVE). "
              + "Sem ela não há como criar a instância de WhatsApp.");
        }

        Provisionamento p = provisionamentos.findByTenantAndProduto(tenant, produto)
                .orElseGet(() -> Provisionamento.builder()
                        .tenant(tenant).produto(produto)
                        .instancia(nomeDaInstancia(tenant))
                        .estado("pendente")
                        .build());

        // Idempotente — mas só se a instância ainda existir de verdade.
        //
        // Apagar uma instância direto na Evolution é operação realista (uma
        // limpeza, um engano). Sem esta conferência, o Portal continuaria
        // entregando o nome e o token de um fantasma, o sistema vendido
        // tentaria enviar por ela e receberia 404 — e não haveria caminho de
        // recuperação, porque o guard devolvia o registro velho para sempre.
        if ("ativo".equals(p.getEstado()) && p.getToken() != null) {
            if (instanciaExiste(p)) return p;
            log.warn("Instância {} não existe mais na Evolution. Recriando com o mesmo nome.",
                     p.getInstancia());
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resposta = http.post()
                    .uri("/instance/create")
                    .header("apikey", chaveGlobal)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body(Map.of(
                            "instanceName", p.getInstancia(),
                            "qrcode", true,
                            "integration", "WHATSAPP-BAILEYS"))
                    .retrieve()
                    .body(Map.class);

            String token = extrairToken(resposta);
            if (token == null || token.isBlank()) {
                // Sem token por instância não dá para seguir: o sistema
                // vendido teria de usar a chave global, que é exatamente o
                // que este serviço existe para evitar.
                p.setEstado("falhou");
                p.setErro("A Evolution criou a instância mas não devolveu credencial própria (hash).");
                log.warn("Instância {} criada sem hash — verifique a versão da Evolution.", p.getInstancia());
            } else {
                p.setToken(token);
                p.setEstado("ativo");
                p.setErro(null);
                log.info("Instância {} provisionada para {}.", p.getInstancia(), tenant.getNome());
            }
        } catch (RestClientResponseException e) {
            // É no corpo que a Evolution explica o motivo real: chave
            // inválida, nome já em uso, versão incompatível.
            p.setEstado("falhou");
            p.setErro("Evolution respondeu HTTP " + e.getStatusCode().value() + ": "
                    + resumir(e.getResponseBodyAsString()));
            log.warn("Falha ao provisionar {}: {}", p.getInstancia(), p.getErro());
        } catch (Exception e) {
            p.setEstado("falhou");
            p.setErro("Não foi possível contatar a Evolution: " + e.getClass().getSimpleName());
            log.warn("Falha ao provisionar {}: {}", p.getInstancia(), e.toString());
        }

        return provisionamentos.save(p);
    }

    /**
     * A instância ainda está lá?
     *
     * Consulta com o token da própria instância: se ela tiver sido apagada,
     * a Evolution responde 404 e o token não vale mais para nada.
     *
     * Na dúvida — timeout, Evolution fora do ar — devolve true. Recriar uma
     * instância que existe seria pior que não recriar: derrubaria o
     * pareamento de uma loja que está funcionando.
     */
    private boolean instanciaExiste(Provisionamento p) {
        try {
            http.get()
                .uri("/instance/connectionState/{nome}", p.getInstancia())
                .header("apikey", p.getToken())
                .retrieve()
                .body(Map.class);
            return true;
        } catch (RestClientResponseException e) {
            return e.getStatusCode().value() != 404;
        } catch (Exception e) {
            log.warn("Não foi possível conferir a instância {}: {}", p.getInstancia(), e.toString());
            return true;
        }
    }

    /**
     * Nome da instância a partir do código do assinante.
     *
     * Previsível de propósito. Quem abrir o painel da Evolution para
     * diagnosticar precisa conseguir dizer de quem é cada instância — um
     * identificador aleatório transformaria isso em consulta ao banco.
     */
    private String nomeDaInstancia(Tenant tenant) {
        String base = prefixo + tenant.getCodigo();
        String nome = base;
        int n = 2;
        while (provisionamentos.existsByInstancia(nome)) nome = base + "-" + n++;
        return nome;
    }

    /**
     * A Evolution v2 devolve a credencial da instância em "hash" — às vezes
     * como texto, às vezes como objeto com "apikey" dentro, dependendo da
     * versão. Aceitar as duas formas evita que um upgrade quebre o
     * provisionamento em silêncio.
     */
    @SuppressWarnings("unchecked")
    private String extrairToken(Map<String, Object> resposta) {
        if (resposta == null) return null;
        Object hash = resposta.get("hash");
        if (hash instanceof String s) return s;
        if (hash instanceof Map<?, ?> m) {
            Object apikey = ((Map<String, Object>) m).get("apikey");
            if (apikey instanceof String s) return s;
        }
        Object direto = resposta.get("apikey");
        return direto instanceof String s ? s : null;
    }

    private String resumir(String corpo) {
        if (corpo == null) return "(sem corpo)";
        String limpo = corpo.replaceAll("\\s+", " ").trim();
        return limpo.length() > 200 ? limpo.substring(0, 200) + "…" : limpo;
    }
}

package br.com.drlog.portal.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ============================================================================
 * CLIENTE DO ASAAS
 *
 * A única parte do sistema que fala com o gateway. Nenhum sistema vendido
 * toca em pagamento — eles só precisam saber se o tenant está ativo.
 * ============================================================================
 */
@Service
public class AsaasClient {

    private static final Logger log = LoggerFactory.getLogger(AsaasClient.class);

    private final RestClient http;
    private final String chave;
    private final String portalUrl;

    public AsaasClient(@Value("${app.asaas.base-url:https://api.asaas.com/v3}") String baseUrl,
                       @Value("${app.asaas.chave:}") String chave,
                       @Value("${app.portal.url:https://drlog.com.br}") String portalUrl) {
        this.chave = chave;
        this.portalUrl = portalUrl.replaceAll("/+$", "");
        this.http = RestClient.builder()
                .baseUrl(baseUrl.replaceAll("/+$", ""))
                // Obrigatório em contas criadas a partir de 06/11/2024. Sem
                // ele o Asaas recusa a requisição, e a mensagem de erro não
                // deixa claro que o problema é este cabeçalho.
                .defaultHeader(HttpHeaders.USER_AGENT, "PortalDrlog (" + portalUrl + ")")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .build();
    }

    public boolean configurado() { return chave != null && !chave.isBlank(); }

    /**
     * Cria o cliente no Asaas, ou devolve o que já existe.
     *
     * A busca por documento vem antes da criação: o mesmo CNPJ cadastrado
     * duas vezes vira dois clientes no gateway, e o histórico de pagamento da
     * loja fica partido entre eles.
     */
    @SuppressWarnings("unchecked")
    public String garantirCliente(String nome, String documento, String email, String telefone) {
        String doc = apenasDigitos(documento);

        if (!doc.isBlank()) {
            Map<String, Object> busca = http.get()
                    .uri(u -> u.path("/customers").queryParam("cpfCnpj", doc).build())
                    .header("access_token", chave)
                    .retrieve().body(Map.class);
            Object dados = busca == null ? null : busca.get("data");
            if (dados instanceof java.util.List<?> lista && !lista.isEmpty()) {
                String id = (String) ((Map<String, Object>) lista.get(0)).get("id");
                log.info("Cliente já existia no Asaas: {}", id);
                return id;
            }
        }

        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("name", nome);
        if (!doc.isBlank()) corpo.put("cpfCnpj", doc);
        if (email != null && !email.isBlank()) corpo.put("email", email);
        String tel = apenasDigitos(telefone);
        if (!tel.isBlank()) corpo.put("mobilePhone", tel);

        Map<String, Object> criado = http.post().uri("/customers")
                .header("access_token", chave)
                .body(corpo).retrieve().body(Map.class);

        String id = criado == null ? null : (String) criado.get("id");
        log.info("Cliente criado no Asaas: {}", id);
        return id;
    }

    /**
     * Cria a assinatura recorrente.
     *
     * billingType UNDEFINED deixa o cliente escolher entre Pix, boleto e
     * cartão na hora de pagar. Fixar um método reduziria a chance de a loja
     * conseguir pagar do jeito que lhe é possível.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> criarAssinatura(String clienteId, int valorCentavos,
                                               String ciclo, String primeiroVencimento,
                                               String descricao) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("customer", clienteId);
        corpo.put("billingType", "UNDEFINED");
        corpo.put("value", valorCentavos / 100.0);
        corpo.put("nextDueDate", primeiroVencimento);
        corpo.put("cycle", "anual".equalsIgnoreCase(ciclo) ? "YEARLY" : "MONTHLY");
        corpo.put("description", descricao);

        return http.post().uri("/subscriptions")
                .header("access_token", chave)
                .body(corpo).retrieve().body(Map.class);
    }

    /**
     * As cobranças de uma assinatura.
     *
     * Usada logo depois de criar a assinatura, para pegar o link da primeira
     * fatura. Sem ele, o cliente sairia da tela sabendo que assinou e sem
     * saber como pagar — e teria de esperar o e-mail do Asaas.
     */
    @SuppressWarnings("unchecked")
    public java.util.List<Map<String, Object>> cobrancasDaAssinatura(String assinaturaId) {
        Map<String, Object> r = http.get()
                .uri("/subscriptions/{id}/payments", assinaturaId)
                .header("access_token", chave)
                .retrieve().body(Map.class);
        Object dados = r == null ? null : r.get("data");
        return dados instanceof java.util.List<?> lista
                ? (java.util.List<Map<String, Object>>) lista
                : java.util.List.of();
    }

    /**
     * Relê uma cobrança direto no Asaas.
     *
     * É o passo que torna o webhook seguro. O endpoint é público; sem
     * reconsultar, quem descobrisse a URL e o token conseguiria declarar
     * pagamentos que não aconteceram. Com a releitura, o webhook vira apenas
     * um aviso de "algo mudou, vá conferir".
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> lerCobranca(String cobrancaId) {
        return http.get().uri("/payments/{id}", cobrancaId)
                .header("access_token", chave)
                .retrieve().body(Map.class);
    }


    // ------------------------------------------------------------------
    // Checkout hospedado — o caminho do teste com cartão
    // ------------------------------------------------------------------

    /**
     * Abre um checkout do Asaas para o teste grátis.
     *
     * O cliente é levado para uma página no domínio do Asaas e digita o cartão
     * lá. O dado do cartão nunca passa por este servidor — e essa é a razão de
     * usar o checkout hospedado em vez da tokenização transparente, que
     * traria um peso de conformidade que uma operação pequena não deve
     * carregar.
     *
     * `nextDueDate` no futuro é o que torna o teste realmente grátis: o Asaas
     * valida o cartão agora e só cobra naquela data. Se a data fosse hoje, a
     * cobrança sairia na hora — e o "14 dias grátis" viraria mentira.
     *
     * Só CREDIT_CARD: o checkout aceita Pix também, mas Pix não pode ser
     * debitado sozinho no fim do teste, que é justamente o que se quer aqui.
     * Quem prefere Pix assina pelo outro caminho e paga na hora.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> abrirCheckoutDeTeste(int valorCentavos, String ciclo,
                                                    String primeiraCobranca, String nomeDoItem,
                                                    String descricao, String referencia) {
        Map<String, Object> assinatura = new LinkedHashMap<>();
        assinatura.put("cycle", "anual".equalsIgnoreCase(ciclo) ? "YEARLY" : "MONTHLY");
        assinatura.put("nextDueDate", primeiraCobranca);

        Map<String, Object> item = new LinkedHashMap<>();
        // O Asaas corta o nome em 30 caracteres; cortar aqui evita que ele
        // chegue truncado no meio de uma palavra na tela do cliente.
        item.put("name", encurtar(nomeDoItem, 30));
        item.put("description", encurtar(descricao, 150));
        item.put("quantity", 1);
        item.put("value", valorCentavos / 100.0);

        Map<String, Object> retorno = new LinkedHashMap<>();
        retorno.put("successUrl", portalUrl + "/assinar/retorno/" + referencia);
        retorno.put("cancelUrl",  portalUrl + "/assinar/retorno/" + referencia);
        retorno.put("expiredUrl", portalUrl + "/assinar/retorno/" + referencia);

        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("billingTypes", java.util.List.of("CREDIT_CARD"));
        corpo.put("chargeTypes", java.util.List.of("RECURRENT"));
        corpo.put("minutesToExpire", 60);
        corpo.put("callback", retorno);
        corpo.put("items", java.util.List.of(item));
        corpo.put("subscription", assinatura);
        corpo.put("externalReference", referencia);

        return http.post().uri("/checkouts")
                .header("access_token", chave)
                .body(corpo).retrieve().body(Map.class);
    }

    /**
     * Relê o checkout no Asaas.
     *
     * A volta do navegador passa pela máquina do cliente, e por isso não vale
     * como prova de que o cartão foi aceito. O que decide é o status lido
     * aqui — mesma regra que já vale para o webhook.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> lerCheckout(String checkoutId) {
        return http.get().uri("/checkouts/{id}", checkoutId)
                .header("access_token", chave)
                .retrieve().body(Map.class);
    }

    /**
     * As assinaturas de um cliente.
     *
     * O checkout cria a assinatura do lado do Asaas, e o id dela não vem de
     * volta de forma garantida. Sem descobrir esse id, os eventos de pagamento
     * seguintes chegariam sem corresponder a nenhuma assinatura daqui — e a
     * renovação nunca estenderia o acesso.
     */
    @SuppressWarnings("unchecked")
    public java.util.List<Map<String, Object>> assinaturasDoCliente(String clienteId) {
        Map<String, Object> r = http.get()
                .uri(u -> u.path("/subscriptions").queryParam("customer", clienteId).build())
                .header("access_token", chave)
                .retrieve().body(Map.class);
        Object dados = r == null ? null : r.get("data");
        return dados instanceof java.util.List<?> lista
                ? (java.util.List<Map<String, Object>>) lista
                : java.util.List.of();
    }

    /**
     * Encerra a assinatura no gateway.
     *
     * É o que faz o cancelamento valer de verdade. Marcar cancelado só aqui
     * dentro e esquecer o Asaas continuaria debitando o cartão do cliente
     * todo mês — o pior defeito possível neste fluxo.
     */
    public void cancelarAssinatura(String assinaturaId) {
        http.delete().uri("/subscriptions/{id}", assinaturaId)
                .header("access_token", chave)
                .retrieve().toBodilessEntity();
    }

    private static String encurtar(String s, int limite) {
        if (s == null) return null;
        return s.length() <= limite ? s : s.substring(0, limite).trim();
    }

    private static String apenasDigitos(String s) {
        return s == null ? "" : s.replaceAll("\\D", "");
    }
}

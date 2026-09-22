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

    public AsaasClient(@Value("${app.asaas.base-url:https://api.asaas.com/v3}") String baseUrl,
                       @Value("${app.asaas.chave:}") String chave,
                       @Value("${app.portal.url:https://drlog.com.br}") String portalUrl) {
        this.chave = chave;
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

    private static String apenasDigitos(String s) {
        return s == null ? "" : s.replaceAll("\\D", "");
    }
}

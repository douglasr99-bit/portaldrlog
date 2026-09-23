package br.com.drlog.portal.service;

import br.com.drlog.portal.model.*;
import br.com.drlog.portal.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * ============================================================================
 * COBRANÇA RECORRENTE
 *
 * Ativa a cobrança de uma assinatura no gateway e reage aos eventos que ele
 * envia. É o que tira a renovação da memória de alguém.
 *
 * A máquina de estados é dirigida pelos eventos de COBRANÇA (PAYMENT_*), não
 * pelos de assinatura. A documentação do Asaas se contradiz sobre a
 * existência dos SUBSCRIPTION_*; o evento de cobrança é o que corresponde a
 * dinheiro tendo entrado, e é nele que dá para confiar.
 * ============================================================================
 */
@Service
public class CobrancaService {

    private static final Logger log = LoggerFactory.getLogger(CobrancaService.class);
    private static final String GATEWAY = "asaas";
    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");

    /**
     * Carência depois do vencimento.
     *
     * Uma loja de bairro atrasa o boleto dois dias com frequência. Cortar o
     * sistema no primeiro dia de atraso gera churn e chamado de suporte — e o
     * sistema cortado é justamente o que a loja usa para faturar e conseguir
     * pagar.
     */
    private static final int DIAS_DE_CARENCIA = 5;

    private final AssinaturaRepository assinaturas;
    private final CobrancaRepository cobrancas;
    private final EventoGatewayRepository eventos;
    private final ContaTenantRepository vinculos;
    private final AsaasClient asaas;
    private final ObjectMapper json = new ObjectMapper();
    private final String tokenDoWebhook;

    public CobrancaService(AssinaturaRepository assinaturas, CobrancaRepository cobrancas,
                           EventoGatewayRepository eventos, ContaTenantRepository vinculos,
                           AsaasClient asaas,
                           @Value("${app.asaas.webhook-token:}") String tokenDoWebhook) {
        this.assinaturas = assinaturas;
        this.cobrancas = cobrancas;
        this.eventos = eventos;
        this.vinculos = vinculos;
        this.asaas = asaas;
        this.tokenDoWebhook = tokenDoWebhook;
    }

    public boolean configurado()   { return asaas.configurado(); }
    public boolean webhookPronto() { return tokenDoWebhook != null && !tokenDoWebhook.isBlank(); }
    public String  tokenDoWebhook(){ return tokenDoWebhook; }

    // ------------------------------------------------------------------
    // Ativar a cobrança
    // ------------------------------------------------------------------

    /**
     * Cria cliente e assinatura no gateway para uma assinatura existente.
     *
     * Idempotente: se já houver assinatura no gateway, não cria outra. Duas
     * assinaturas no Asaas para a mesma loja cobrariam duas vezes pelo mesmo
     * acesso, e o cliente veria dois boletos.
     */
    @Transactional
    public Assinatura ativarCobranca(UUID assinaturaId) {
        if (!asaas.configurado())
            throw new AdminService.Recusa(
                "A chave do Asaas não está configurada no Portal (APP_ASAAS_CHAVE).");

        Assinatura a = assinaturas.findById(assinaturaId)
                .orElseThrow(() -> new AdminService.Recusa("Assinatura não encontrada."));

        if (a.cobrancaAutomatica()) return a;

        Tenant tenant = a.getTenant();
        Conta responsavel = donoDe(tenant)
                .orElseThrow(() -> new AdminService.Recusa(
                        "A loja não tem responsável vinculado — não há para quem emitir a cobrança."));

        if (tenant.getDocumento() == null || tenant.getDocumento().isBlank())
            throw new AdminService.Recusa(
                "A loja está sem CNPJ ou CPF. O Asaas exige o documento para emitir cobrança.");

        String clienteId = asaas.garantirCliente(
                tenant.getNome(), tenant.getDocumento(), responsavel.getEmail(), null);
        if (clienteId == null)
            throw new AdminService.Recusa("O Asaas não devolveu o identificador do cliente.");

        // O primeiro vencimento é o fim do acesso já pago. Cobrar hoje por um
        // período que a loja já tem seria cobrar duas vezes.
        LocalDate primeiroVencimento = a.getAcessoAte() != null
                ? LocalDate.ofInstant(a.getAcessoAte(), FUSO)
                : LocalDate.now(FUSO);
        if (primeiroVencimento.isBefore(LocalDate.now(FUSO)))
            primeiroVencimento = LocalDate.now(FUSO);

        Map<String, Object> criada = asaas.criarAssinatura(
                clienteId,
                a.getPlano().getPrecoCentavos(),
                a.getPlano().getCiclo().name(),
                primeiroVencimento.format(DateTimeFormatter.ISO_LOCAL_DATE),
                "%s — %s".formatted(a.getProduto().getNome(), a.getPlano().getNome()));

        String assinaturaExterna = criada == null ? null : (String) criada.get("id");
        if (assinaturaExterna == null)
            throw new AdminService.Recusa("O Asaas não devolveu o identificador da assinatura.");

        a.setGateway(GATEWAY);
        a.setGatewayClienteId(clienteId);
        a.setGatewayAssinaturaId(assinaturaExterna);

        // Registra a primeira cobrança já aqui, pelo link da fatura: sem ele,
        // o cliente sai da tela sabendo que assinou e sem saber como pagar.
        try {
            asaas.cobrancasDaAssinatura(assinaturaExterna).stream().findFirst()
                 .ifPresent(primeira -> registrarCobranca(a, primeira));
        } catch (Exception e) {
            log.warn("Assinatura {} criada, mas não consegui ler a primeira cobrança: {}",
                     assinaturaExterna, e.toString());
        }
        log.info("Cobrança ativada para {}: assinatura {} no Asaas, primeiro vencimento {}",
                 tenant.getNome(), assinaturaExterna, primeiroVencimento);
        return assinaturas.save(a);
    }

    private Optional<Conta> donoDe(Tenant tenant) {
        return vinculos.findByTenantAndPapel(tenant, Papel.dono).map(ContaTenant::getConta);
    }

    // ------------------------------------------------------------------
    // Receber o webhook
    // ------------------------------------------------------------------

    /**
     * Guarda o evento e diz se ele é novo.
     *
     * Gravar primeiro e processar depois é o que permite responder 2xx
     * rápido. O Asaas interrompe a fila após 15 falhas consecutivas: um
     * processamento lento dentro do handler não atrasaria um evento, e sim
     * todos.
     *
     * O retorno falso significa "já vi este" — entrega repetida é parte do
     * desenho do Asaas, não erro.
     */
    @Transactional
    public Optional<EventoGateway> registrar(String eventoId, String tipo, String corpo) {
        if (eventoId == null || eventoId.isBlank()) return Optional.empty();
        if (eventos.existsByGatewayAndEventoId(GATEWAY, eventoId)) {
            log.debug("Evento {} já registrado — entrega repetida.", eventoId);
            return Optional.empty();
        }
        return Optional.of(eventos.save(EventoGateway.builder()
                .gateway(GATEWAY).eventoId(eventoId).tipo(tipo).payload(corpo).build()));
    }

    /**
     * Processa um evento já guardado.
     *
     * Nunca confia no corpo recebido: relê a cobrança no Asaas antes de mudar
     * qualquer estado. O endpoint é público, e sem a releitura quem
     * descobrisse a URL e o token conseguiria declarar pagamentos que não
     * aconteceram.
     */
    @Transactional
    public void processar(UUID eventoInternoId) {
        EventoGateway evento = eventos.findById(eventoInternoId).orElse(null);
        if (evento == null || evento.getProcessadoEm() != null) return;

        try {
            JsonNode raiz = json.readTree(evento.getPayload());
            String tipo = texto(raiz, "event");
            JsonNode pagamento = raiz.get("payment");

            if (pagamento == null || pagamento.isNull()) {
                concluir(evento, "evento sem cobrança — ignorado");
                return;
            }

            String cobrancaId = texto(pagamento, "id");
            Map<String, Object> real = asaas.lerCobranca(cobrancaId);
            if (real == null) {
                concluir(evento, "cobrança " + cobrancaId + " não encontrada no Asaas");
                return;
            }

            aplicar(tipo, real);
            concluir(evento, null);

        } catch (Exception e) {
            log.warn("Falha ao processar o evento {}: {}", evento.getEventoId(), e.toString());
            evento.setErro(resumir(e.toString()));
            eventos.save(evento);
        }
    }

    /**
     * Onde o estado muda, a partir do que o Asaas diz ser verdade.
     */
    private void aplicar(String tipo, Map<String, Object> cobrancaReal) {
        String assinaturaExterna = (String) cobrancaReal.get("subscription");
        if (assinaturaExterna == null) {
            log.debug("Cobrança avulsa, sem assinatura — nada a fazer.");
            return;
        }

        Assinatura a = assinaturas
                .findByGatewayAndGatewayAssinaturaId(GATEWAY, assinaturaExterna).orElse(null);
        if (a == null) {
            log.warn("Assinatura {} do Asaas não corresponde a nenhuma aqui.", assinaturaExterna);
            return;
        }

        registrarCobranca(a, cobrancaReal);

        String status = String.valueOf(cobrancaReal.get("status"));
        switch (status) {
            // Dinheiro entrou: estende o acesso por um ciclo.
            case "CONFIRMED", "RECEIVED", "RECEIVED_IN_CASH" -> {
                Instant base = (a.getAcessoAte() != null && a.getAcessoAte().isAfter(Instant.now()))
                        ? a.getAcessoAte() : Instant.now();
                a.setAcessoAte(somarCiclo(base, a.getPlano().getCiclo()));
                a.setEstado(EstadoAssinatura.ativa);
                a.setCanceladaEm(null);
                log.info("Pagamento confirmado para {} — acesso até {}",
                         a.getTenant().getNome(), a.getAcessoAte());
            }
            // Venceu e não pagou: entra na carência, ainda com acesso.
            //
            // A carência é dada estendendo a data, não criando um estado
            // especial. Assim o bloqueio acontece sozinho quando ela termina,
            // sem depender de nenhuma rotina rodar na hora certa.
            case "OVERDUE" -> {
                Instant limite = Instant.now().plus(Duration.ofDays(DIAS_DE_CARENCIA));
                if (a.getAcessoAte() == null || a.getAcessoAte().isBefore(limite))
                    a.setAcessoAte(limite);
                a.setEstado(EstadoAssinatura.atrasada);
                log.info("Pagamento em atraso para {} — carência até {}",
                         a.getTenant().getNome(), a.getAcessoAte());
            }
            // Estorno ou chargeback: o dinheiro voltou.
            case "REFUNDED", "CHARGEBACK_REQUESTED", "CHARGEBACK_DISPUTE" -> {
                a.setEstado(EstadoAssinatura.suspensa);
                log.warn("Estorno em {} — assinatura suspensa", a.getTenant().getNome());
            }
            default -> log.debug("Status {} ({}): sem mudança de estado.", status, tipo);
        }
        assinaturas.save(a);
    }

    private void registrarCobranca(Assinatura a, Map<String, Object> real) {
        String externa = (String) real.get("id");
        Cobranca c = cobrancas.findByGatewayAndExternaId(GATEWAY, externa)
                .orElseGet(() -> Cobranca.builder()
                        .assinatura(a).gateway(GATEWAY).externaId(externa).build());

        c.setValorCentavos(centavos(real.get("value")));
        c.setStatus(String.valueOf(real.get("status")));
        c.setVencimento(data(real.get("dueDate")));
        c.setLink((String) real.get("invoiceUrl"));
        Object pago = real.get("paymentDate") != null ? real.get("paymentDate") : real.get("clientPaymentDate");
        LocalDate diaPago = data(pago);
        c.setPagoEm(diaPago == null ? null : diaPago.atStartOfDay(FUSO).toInstant());
        cobrancas.save(c);
    }

    private void concluir(EventoGateway evento, String observacao) {
        evento.setProcessadoEm(Instant.now());
        evento.setErro(observacao);
        eventos.save(evento);
    }

    @Transactional(readOnly = true)
    public List<EventoGateway> ultimosEventos() { return eventos.findTop30ByOrderByRecebidoEmDesc(); }

    /**
     * O cliente assinando por conta própria, a partir do teste.
     *
     * O documento é pedido aqui, e não no cadastro: exigir CNPJ para começar
     * um teste afasta quem só queria experimentar. Na hora de pagar, ele é
     * inevitável — o Asaas exige.
     */
    @Transactional
    public Assinatura assinar(UUID assinaturaId, String documento) {
        Assinatura a = assinaturas.findById(assinaturaId)
                .orElseThrow(() -> new AdminService.Recusa("Assinatura não encontrada."));

        if (documento != null && !documento.isBlank())
            a.getTenant().setDocumento(documento.trim());

        return ativarCobranca(assinaturaId);
    }

    /** A fatura em aberto, para a tela oferecer o link de pagamento. */
    @Transactional(readOnly = true)
    public Optional<Cobranca> faturaEmAberto(Assinatura a) {
        return cobrancas.findByAssinaturaOrderByVencimentoDesc(a).stream()
                .filter(c -> c.getLink() != null && !c.getLink().isBlank())
                .filter(c -> !List.of("RECEIVED", "CONFIRMED", "RECEIVED_IN_CASH").contains(c.getStatus()))
                .findFirst();
    }

    @Transactional(readOnly = true)
    public List<Cobranca> cobrancasDe(Assinatura a) {
        return cobrancas.findByAssinaturaOrderByVencimentoDesc(a);
    }

    /** A fila do que chegou e ainda não foi tratado. */
    @Transactional(readOnly = true)
    public List<EventoGateway> pendentes() { return eventos.findByProcessadoEmIsNullOrderByRecebidoEmAsc(); }

    private static Instant somarCiclo(Instant base, Ciclo ciclo) {
        return ciclo == Ciclo.anual ? base.plus(Duration.ofDays(365)) : base.plus(Duration.ofDays(30));
    }

    private static int centavos(Object valor) {
        if (valor == null) return 0;
        return (int) Math.round(Double.parseDouble(String.valueOf(valor)) * 100);
    }

    private static LocalDate data(Object v) {
        if (v == null) return null;
        try { return LocalDate.parse(String.valueOf(v).substring(0, 10)); }
        catch (Exception e) { return null; }
    }

    private static String texto(JsonNode no, String campo) {
        JsonNode v = no == null ? null : no.get(campo);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String resumir(String s) {
        return s.length() > 480 ? s.substring(0, 480) + "…" : s;
    }
}

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
    private final LiberacaoService liberacao;
    private final ObjectMapper json = new ObjectMapper();
    private final String tokenDoWebhook;
    private final int diasDeTeste;

    public CobrancaService(AssinaturaRepository assinaturas, CobrancaRepository cobrancas,
                           EventoGatewayRepository eventos, ContaTenantRepository vinculos,
                           AsaasClient asaas, LiberacaoService liberacao,
                           @Value("${app.asaas.webhook-token:}") String tokenDoWebhook,
                           @Value("${app.cadastro.dias-de-teste:14}") int diasDeTeste) {
        this.assinaturas = assinaturas;
        this.cobrancas = cobrancas;
        this.eventos = eventos;
        this.vinculos = vinculos;
        this.asaas = asaas;
        this.liberacao = liberacao;
        this.tokenDoWebhook = tokenDoWebhook;
        this.diasDeTeste = diasDeTeste;
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

            // O checkout do teste tem eventos próprios, e eles não trazem
            // cobrança nenhuma — o cartão foi apenas validado, a primeira
            // cobrança só sai no fim do teste.
            if (tipo != null && tipo.startsWith("CHECKOUT_")) {
                concluir(evento, aplicarCheckout(tipo, raiz.get("checkout")));
                return;
            }

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
                // Passa pela liberação, e não direto no estado, porque este é
                // também o momento em que um cliente que pagou na hora — sem
                // teste — recebe o acesso e o WhatsApp pela primeira vez.
                liberacao.liberar(a, EstadoAssinatura.ativa, somarCiclo(base, a.getPlano().getCiclo()));
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


    // ------------------------------------------------------------------
    // Teste com cartão
    // ------------------------------------------------------------------

    /**
     * Abre o checkout do teste e devolve o link para onde mandar o cliente.
     *
     * O cartão é digitado no domínio do Asaas, nunca aqui. E a primeira
     * cobrança fica agendada para o fim do teste: o Asaas valida o cartão
     * agora e só debita naquela data.
     */
    @Transactional
    public String iniciarTesteComCartao(Assinatura a) {
        if (!asaas.configurado())
            throw new AdminService.Recusa(
                "A cobrança por cartão não está configurada no Portal (APP_ASAAS_CHAVE).");

        LocalDate primeiraCobranca = LocalDate.now(FUSO).plusDays(diasDeTeste);

        Map<String, Object> checkout = asaas.abrirCheckoutDeTeste(
                a.getPlano().getPrecoCentavos(),
                a.getPlano().getCiclo().name(),
                primeiraCobranca.format(DateTimeFormatter.ISO_LOCAL_DATE) + " 12:00:00",
                a.getProduto().getNome(),
                "%s — %d dias grátis, primeira cobrança em %s".formatted(
                        a.getPlano().getNome(), diasDeTeste,
                        primeiraCobranca.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))),
                a.getId().toString());

        String id   = checkout == null ? null : (String) checkout.get("id");
        String link = checkout == null ? null : (String) checkout.get("link");
        if (link == null)
            throw new AdminService.Recusa("O Asaas não devolveu o endereço do checkout.");

        a.setGateway(GATEWAY);
        a.setGatewayCheckoutId(id);
        a.setCheckoutLink(link);
        assinaturas.save(a);

        log.info("Checkout de teste aberto para {}: {} (primeira cobrança em {})",
                 a.getTenant().getNome(), id, primeiraCobranca);
        return link;
    }

    /**
     * Confere no Asaas se o cartão foi aceito e libera o teste.
     *
     * A volta do navegador passa pela máquina do cliente e por isso não prova
     * nada: quem editasse a URL de sucesso entraria sem cartão. O que decide
     * é a existência da assinatura do lado do Asaas — ela só nasce quando o
     * cartão é aprovado.
     *
     * Chamado pela volta da tela, pelo webhook e pelo botão do painel. Seguro
     * nos três: a liberação é idempotente.
     */
    @Transactional
    public boolean confirmarCheckout(UUID assinaturaId) {
        Assinatura a = assinaturas.findById(assinaturaId)
                .orElseThrow(() -> new AdminService.Recusa("Assinatura não encontrada."));

        if (a.getEstado() != EstadoAssinatura.aguardando_pagamento) return true;
        if (a.getGatewayCheckoutId() == null) return false;

        Optional<Map<String, Object>> assinaturaExterna =
                asaas.assinaturaDoCheckout(a.getGatewayCheckoutId());
        if (assinaturaExterna.isEmpty()) return false;

        liberarTeste(a, assinaturaExterna.get());
        return true;
    }

    /**
     * Amarra a assinatura criada pelo checkout e liga o acesso.
     *
     * Guardar o id da assinatura do lado do Asaas é o passo que não pode
     * faltar: sem ele, os eventos de pagamento seguintes chegariam sem
     * corresponder a nada aqui, e a renovação nunca estenderia o acesso — o
     * cliente seria cobrado e perderia o sistema no mesmo dia.
     */
    private void liberarTeste(Assinatura a, Map<String, Object> externa) {
        a.setGateway(GATEWAY);
        a.setGatewayClienteId((String) externa.get("customer"));
        a.setGatewayAssinaturaId((String) externa.get("id"));

        Instant fimDoTeste = fimDoTestePelaPrimeiraCobranca(a)
                .orElse(Instant.now().plus(Duration.ofDays(diasDeTeste)));

        liberacao.liberar(a, EstadoAssinatura.trial, fimDoTeste);
        assinaturas.save(a);
    }

    /**
     * O fim do teste é o dia em que o cartão será debitado.
     *
     * Vem do vencimento da PRIMEIRA COBRANÇA, não do `nextDueDate` da
     * assinatura: aquele campo já aponta para o ciclo seguinte assim que a
     * primeira cobrança é gerada. Usá-lo daria ao cliente um mês inteiro de
     * graça — e faria a data na tela discordar da data do débito.
     *
     * O acesso vai até o FIM desse dia. Terminar às 00:00 cortaria o sistema
     * de manhã enquanto a cobrança ainda estaria sendo processada à tarde.
     */
    private Optional<Instant> fimDoTestePelaPrimeiraCobranca(Assinatura a) {
        try {
            return asaas.cobrancasDaAssinatura(a.getGatewayAssinaturaId()).stream()
                    .peek(primeira -> registrarCobranca(a, primeira))
                    .map(primeira -> data(primeira.get("dueDate")))
                    .filter(java.util.Objects::nonNull)
                    .min(java.time.LocalDate::compareTo)
                    .map(d -> d.plusDays(1).atStartOfDay(FUSO).toInstant());
        } catch (Exception e) {
            log.warn("Teste de {} liberado, mas não consegui ler a primeira cobrança: {}",
                     a.getTenant().getNome(), e.toString());
            return Optional.empty();
        }
    }

    /**
     * Retoma um cadastro que parou no meio.
     *
     * Existe porque um cadastro em `aguardando_pagamento` podia virar beco sem
     * saída de duas formas: o checkout nunca chegou a ser criado, porque a
     * chamada ao Asaas falhou depois de a conta já existir; ou o checkout foi
     * criado e **expirou** — eles duram 60 minutos, e quem volta no dia
     * seguinte clicaria num link morto.
     *
     * Nos dois casos a pessoa ficava com uma conta inutilizável e sem poder
     * recadastrar o mesmo e-mail, que é o pior desfecho possível para alguém
     * que já tinha decidido comprar.
     *
     * Confirmar vem antes de criar, sempre: se o checkout guardado já tiver
     * sido pago, criar outro faria o Portal esquecer a assinatura que nasceu
     * do primeiro — e o cliente pagaria sem que a renovação encontrasse a
     * quem pertence.
     *
     * Devolve para onde mandar o cliente, ou vazio quando não há para onde ir
     * além do próprio painel.
     */
    @Transactional
    public Optional<String> retomar(UUID assinaturaId) {
        Assinatura a = assinaturas.findById(assinaturaId)
                .orElseThrow(() -> new AdminService.Recusa("Assinatura não encontrada."));

        if (a.getEstado() != EstadoAssinatura.aguardando_pagamento) return Optional.empty();
        if (a.getGatewayCheckoutId() != null && confirmarCheckout(assinaturaId)) return Optional.empty();

        // Quem escolheu "Assinar agora" informou o documento no cadastro. É o
        // que distingue os dois caminhos depois do fato, sem guardar a escolha
        // num campo que só serviria para isto.
        String documento = a.getTenant().getDocumento();
        if (documento != null && !documento.isBlank()) {
            ativarCobranca(assinaturaId);
            return Optional.empty();
        }
        return Optional.of(iniciarTesteComCartao(a));
    }

    /** O mesmo desfecho, quando quem avisa é o webhook e não o navegador. */
    private String aplicarCheckout(String tipo, JsonNode checkout) {
        String checkoutId = texto(checkout, "id");
        if (checkoutId == null) return "evento de checkout sem identificador — ignorado";

        Assinatura a = assinaturas.findByGatewayCheckoutId(checkoutId).orElse(null);
        if (a == null) return "checkout " + checkoutId + " não corresponde a nenhuma assinatura aqui";

        if (!"CHECKOUT_PAID".equals(tipo)) return "checkout " + tipo.toLowerCase() + " — sem acesso liberado";

        confirmarCheckout(a.getId());
        return null;
    }

    // ------------------------------------------------------------------
    // Cancelar
    // ------------------------------------------------------------------

    /**
     * O cliente pede para não renovar.
     *
     * Interrompe a cobrança no gateway e mantém o acesso até o dia já pago —
     * quem pagou até o dia 30 tem direito ao dia 29. O acesso termina sozinho
     * quando a data passa, sem depender de nenhuma rotina rodar na hora certa.
     *
     * O cancelamento no Asaas vem primeiro de propósito: marcar cancelado
     * aqui e falhar lá continuaria debitando o cartão do cliente todo mês,
     * que é o pior desfecho possível deste fluxo.
     */
    @Transactional
    public Assinatura cancelarRenovacao(UUID assinaturaId) {
        Assinatura a = assinaturas.findById(assinaturaId)
                .orElseThrow(() -> new AdminService.Recusa("Assinatura não encontrada."));

        if (a.isRenovacaoCancelada()) return a;

        if (a.cobrancaAutomatica()) {
            try {
                asaas.cancelarAssinatura(a.getGatewayAssinaturaId());
            } catch (Exception e) {
                log.error("Falha ao cancelar a assinatura {} no Asaas: {}",
                          a.getGatewayAssinaturaId(), e.toString());
                throw new AdminService.Recusa(
                    "Não consegui cancelar a cobrança no gateway agora. "
                  + "Não marquei como cancelada para não deixar você sendo cobrado sem saber. "
                  + "Tente de novo em instantes ou fale com a gente no WhatsApp.");
            }
        }

        a.setRenovacaoCancelada(true);
        a.setCanceladaEm(Instant.now());

        // Quem nunca chegou a ter acesso não precisa esperar data nenhuma.
        if (a.getEstado() == EstadoAssinatura.aguardando_pagamento)
            a.setEstado(EstadoAssinatura.cancelada);

        log.info("Renovação cancelada por {} — acesso mantido até {}",
                 a.getTenant().getNome(), a.getAcessoAte());
        return assinaturas.save(a);
    }

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

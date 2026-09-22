package br.com.drlog.portal.web;

import br.com.drlog.portal.model.EventoGateway;
import br.com.drlog.portal.service.CobrancaService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;

/**
 * ============================================================================
 * WEBHOOK DO ASAAS
 *
 * O ponto mais exposto do Portal: uma URL pública que, se acreditasse no que
 * recebe, permitiria a qualquer um declarar pagamentos que não aconteceram.
 *
 * Três defesas, nesta ordem:
 *
 *   1. o cabeçalho asaas-access-token, conferido em tempo constante;
 *   2. idempotência por id de evento, porque a entrega é "at least once";
 *   3. releitura da cobrança na API do Asaas antes de mudar qualquer estado.
 *
 * A terceira é a que realmente sustenta: com ela, o webhook vira apenas um
 * aviso de "algo mudou, vá conferir". O pior que um atacante consegue é fazer
 * o Portal reler a verdade.
 * ============================================================================
 */
@RestController
@RequestMapping("/api/gateway")
public class WebhookAsaasController {

    private static final Logger log = LoggerFactory.getLogger(WebhookAsaasController.class);

    private final CobrancaService cobranca;
    private final ObjectMapper json = new ObjectMapper();

    public WebhookAsaasController(CobrancaService cobranca) {
        this.cobranca = cobranca;
    }

    @PostMapping("/asaas")
    public ResponseEntity<?> receber(@RequestBody String corpo,
                                     @RequestHeader(value = "asaas-access-token",
                                                    required = false) String apresentado) {

        // Sem token configurado o webhook fica fechado, não aberto. Um
        // endpoint que altera estado de assinatura não pode ter modo
        // permissivo por omissão de configuração.
        if (!cobranca.webhookPronto()) {
            log.warn("Webhook do Asaas chamado sem APP_ASAAS_WEBHOOK_TOKEN configurado.");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("erro", "webhook não configurado"));
        }
        if (!igual(cobranca.tokenDoWebhook(), apresentado)) {
            log.warn("Webhook do Asaas com token inválido.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String eventoId = null, tipo = null;
        try {
            JsonNode raiz = json.readTree(corpo);
            eventoId = valor(raiz, "id");
            tipo = valor(raiz, "event");
        } catch (Exception e) {
            log.warn("Webhook do Asaas com corpo ilegível: {}", e.toString());
        }

        // Sem id não há como garantir idempotência. Responder 200 evita que o
        // Asaas fique reenviando algo que nunca será processável — e o log
        // guarda o registro.
        if (eventoId == null) {
            log.warn("Webhook do Asaas sem id de evento. Corpo: {}",
                     corpo.length() > 300 ? corpo.substring(0, 300) + "…" : corpo);
            return ResponseEntity.ok(Map.of("recebido", true, "processado", false));
        }

        Optional<EventoGateway> novo = cobranca.registrar(eventoId, tipo, corpo);

        // Responde já. O Asaas interrompe a fila após 15 falhas consecutivas,
        // e ela é sequencial: um processamento lento aqui atrasaria todos os
        // eventos seguintes, de todos os assinantes.
        novo.ifPresent(e -> processarDepois(e.getId()));

        return ResponseEntity.ok(Map.of(
                "recebido", true,
                "repetido", novo.isEmpty()));
    }

    @Async
    void processarDepois(java.util.UUID id) {
        try {
            cobranca.processar(id);
        } catch (Exception e) {
            log.warn("Falha no processamento assíncrono do evento {}: {}", id, e.toString());
        }
    }

    /**
     * Comparação de tempo constante: um equals() comum sai no primeiro
     * caractere diferente, e essa diferença é medível.
     */
    private boolean igual(String esperado, String apresentado) {
        if (apresentado == null) return false;
        return MessageDigest.isEqual(esperado.getBytes(StandardCharsets.UTF_8),
                                     apresentado.getBytes(StandardCharsets.UTF_8));
    }

    private static String valor(JsonNode no, String campo) {
        JsonNode v = no == null ? null : no.get(campo);
        return v == null || v.isNull() ? null : v.asText();
    }
}

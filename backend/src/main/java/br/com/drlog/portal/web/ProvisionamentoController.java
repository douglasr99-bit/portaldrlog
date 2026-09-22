package br.com.drlog.portal.web;

import br.com.drlog.portal.model.Assinatura;
import br.com.drlog.portal.model.Produto;
import br.com.drlog.portal.model.Provisionamento;
import br.com.drlog.portal.model.Tenant;
import br.com.drlog.portal.repository.AssinaturaRepository;
import br.com.drlog.portal.repository.ProdutoRepository;
import br.com.drlog.portal.repository.TenantRepository;
import br.com.drlog.portal.service.ProvisionamentoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * ============================================================================
 * CANAL SERVIDOR-A-SERVIDOR
 *
 * Por onde um sistema vendido busca a credencial da instância de WhatsApp do
 * seu assinante.
 *
 * Separado do token de acesso de propósito: aquele passa pelo navegador do
 * usuário, e o que estiver dentro dele é público para quem o tiver em mãos.
 * Credencial de instância não pode sair por ali.
 * ============================================================================
 */
@RestController
@RequestMapping("/api/sistemas")
public class ProvisionamentoController {

    private static final Logger log = LoggerFactory.getLogger(ProvisionamentoController.class);

    private final TenantRepository tenants;
    private final ProdutoRepository produtos;
    private final AssinaturaRepository assinaturas;
    private final ProvisionamentoService provisionamento;
    private final String segredo;

    public ProvisionamentoController(TenantRepository tenants, ProdutoRepository produtos,
                                     AssinaturaRepository assinaturas,
                                     ProvisionamentoService provisionamento,
                                     @Value("${app.sistemas.segredo:}") String segredo) {
        this.tenants = tenants;
        this.produtos = produtos;
        this.assinaturas = assinaturas;
        this.provisionamento = provisionamento;
        this.segredo = segredo;
    }

    @GetMapping("/provisionamento/{produtoCodigo}/{tenantCodigo}")
    public ResponseEntity<?> buscar(@PathVariable String produtoCodigo,
                                    @PathVariable String tenantCodigo,
                                    @RequestHeader(value = "X-Sistema-Segredo", required = false) String apresentado) {

        // Sem segredo configurado o canal fica fechado, em vez de aberto.
        // Um endpoint que entrega credenciais não pode ter modo permissivo
        // por omissão de configuração.
        if (segredo == null || segredo.isBlank()) {
            log.warn("Canal de provisionamento consultado sem APP_SISTEMAS_SEGREDO configurado.");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("erro", "canal de provisionamento não configurado"));
        }
        if (!comparacaoConstante(segredo, apresentado)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Optional<Tenant> tenant = tenants.findByCodigo(tenantCodigo);
        Optional<Produto> produto = produtos.findByCodigo(produtoCodigo);
        if (tenant.isEmpty() || produto.isEmpty()) return ResponseEntity.notFound().build();

        // A credencial só sai se a assinatura permitir acesso agora. Sem esta
        // conferência, um assinante cancelado continuaria disparando WhatsApp
        // enquanto o sistema tivesse a credencial em cache.
        Assinatura assinatura = assinaturas.findByTenantAndProduto(tenant.get(), produto.get()).orElse(null);
        if (assinatura == null || !assinatura.permiteAcesso()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("erro", "assinatura não permite acesso"));
        }

        Provisionamento p = provisionamento.de(tenant.get(), produto.get()).orElse(null);
        if (p == null || !"ativo".equals(p.getEstado()) || p.getToken() == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("erro", "instância ainda não provisionada"));
        }

        return ResponseEntity.ok(Map.of(
                "tenant",    tenant.get().getCodigo(),
                "lojaNome",  tenant.get().getNome(),
                "instancia", p.getInstancia(),
                "token",     p.getToken()));
    }

    /**
     * Comparação de tempo constante.
     *
     * Um equals() comum sai no primeiro caractere diferente, e a diferença de
     * tempo entre "errou no primeiro" e "errou no décimo" é medível — o que
     * permite descobrir o segredo caractere a caractere.
     */
    private boolean comparacaoConstante(String esperado, String apresentado) {
        if (apresentado == null) return false;
        return MessageDigest.isEqual(esperado.getBytes(StandardCharsets.UTF_8),
                                     apresentado.getBytes(StandardCharsets.UTF_8));
    }
}

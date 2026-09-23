package br.com.drlog.portal.service;

import br.com.drlog.portal.model.*;
import br.com.drlog.portal.repository.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ============================================================================
 * ADMINISTRAÇÃO DA PLATAFORMA
 *
 * Cria assinantes, renova e muda o estado das assinaturas — as operações que
 * até aqui só existiam como INSERT e UPDATE digitados à mão no psql.
 *
 * Existe antes da cobrança automática de propósito: enquanto o pagamento for
 * combinado no WhatsApp, é esta tela que registra o resultado. Quando o
 * gateway existir, ele passa a chamar os mesmos métodos.
 * ============================================================================
 */
@Service
public class AdminService {

    private final ContaRepository contas;
    private final TenantRepository tenants;
    private final ContaTenantRepository vinculos;
    private final AssinaturaRepository assinaturas;
    private final PlanoRepository planos;
    private final ProvisionamentoRepository provisionamentos;
    private final PasswordEncoder encoder;

    public AdminService(ContaRepository contas, TenantRepository tenants,
                        ContaTenantRepository vinculos, AssinaturaRepository assinaturas,
                        PlanoRepository planos, ProvisionamentoRepository provisionamentos,
                        PasswordEncoder encoder) {
        this.contas = contas;
        this.tenants = tenants;
        this.vinculos = vinculos;
        this.assinaturas = assinaturas;
        this.planos = planos;
        this.provisionamentos = provisionamentos;
        this.encoder = encoder;
    }

    /** Erro previsto, com mensagem que a tela mostra ao administrador. */
    public static class Recusa extends RuntimeException {
        public Recusa(String mensagem) { super(mensagem); }
    }

    // ------------------------------------------------------------------
    // Criar assinante
    // ------------------------------------------------------------------

    /**
     * Cria loja, conta do responsável, vínculo e assinatura — numa transação
     * só.
     *
     * Ou nasce tudo, ou não nasce nada. Criar a loja e falhar na conta
     * deixaria um assinante sem ninguém capaz de entrar nele, e o conserto
     * seria exatamente o SQL na mão que esta tela veio eliminar.
     */
    @Transactional
    public Tenant criarAssinante(String nomeLoja, String documento, String codigoPedido,
                                 UUID planoId, String email, String nomeResponsavel,
                                 String senha) {

        if (vazio(nomeLoja))        throw new Recusa("Informe o nome da loja.");
        if (vazio(email))           throw new Recusa("Informe o e-mail do responsável.");
        if (vazio(nomeResponsavel)) throw new Recusa("Informe o nome do responsável.");
        if (senha == null || senha.length() < 8)
            throw new Recusa("A senha precisa ter pelo menos 8 caracteres.");
        if (planoId == null)        throw new Recusa("Escolha um plano.");

        if (contas.buscarPorEmail(email).isPresent())
            throw new Recusa("Já existe uma conta com o e-mail " + email + ".");

        Plano plano = planos.findById(planoId)
                .orElseThrow(() -> new Recusa("Plano não encontrado."));

        String codigo = vazio(codigoPedido) ? codigoDisponivelPara(nomeLoja) : codigoPedido.trim();
        if (tenants.existsByCodigo(codigo))
            throw new Recusa("Já existe um assinante com o código " + codigo + ".");

        Tenant tenant = tenants.save(Tenant.builder()
                .codigo(codigo)
                .nome(nomeLoja.trim())
                .documento(vazio(documento) ? null : documento.trim())
                .build());

        Conta conta = contas.save(Conta.builder()
                .email(email.trim())
                .senhaHash(encoder.encode(senha))
                .nome(nomeResponsavel.trim())
                .ativa(true)
                .admin(false)
                .build());

        vinculos.save(ContaTenant.builder()
                .conta(conta).tenant(tenant).papel(Papel.dono)
                .build());

        assinaturas.save(Assinatura.builder()
                .tenant(tenant)
                .produto(plano.getProduto())
                .plano(plano)
                .estado(EstadoAssinatura.ativa)
                .acessoAte(somarCiclo(Instant.now(), plano.getCiclo()))
                .build());

        return tenant;
    }

    /**
     * Código a partir do nome: "Sapataria Boa Vista" vira "sapataria-boa-vista".
     *
     * É o valor que vai para dentro dos dados de todos os sistemas vendidos e
     * que nunca mais muda. Legível de propósito — quando alguém for ler um log
     * ou uma consulta, um nome diz de quem é o dado; um uuid não.
     */
    public String codigoDisponivelPara(String nome) {
        String base = Normalizer.normalize(nome.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (base.length() > 48) base = base.substring(0, 48).replaceAll("-$", "");
        if (base.isBlank()) base = "assinante";

        // Colisão de nome é esperada: duas "Sapataria Central" em cidades
        // diferentes. O sufixo evita que a segunda seja recusada.
        String codigo = base;
        int n = 2;
        while (tenants.existsByCodigo(codigo)) codigo = base + "-" + n++;
        return codigo;
    }

    /**
     * Contrata um sistema para um assinante que já existe.
     *
     * É o caminho para a loja que já estava no ar antes do Portal, e para
     * quem contrata um segundo sistema depois. Sem isto, um assinante sem
     * assinatura fica visível na tela e intocável — dá para ver o problema e
     * não dá para resolver.
     */
    @Transactional
    public Assinatura contratar(UUID tenantId, UUID planoId) {
        Tenant tenant = tenants.findById(tenantId)
                .orElseThrow(() -> new Recusa("Assinante não encontrado."));
        Plano plano = planos.findById(planoId)
                .orElseThrow(() -> new Recusa("Plano não encontrado."));

        // O banco já impede com uma UNIQUE; aqui a recusa vira mensagem em
        // vez de erro de integridade na cara do administrador.
        if (assinaturas.findByTenantAndProduto(tenant, plano.getProduto()).isPresent())
            throw new Recusa("%s já tem uma assinatura de %s."
                    .formatted(tenant.getNome(), plano.getProduto().getNome()));

        return assinaturas.save(Assinatura.builder()
                .tenant(tenant)
                .produto(plano.getProduto())
                .plano(plano)
                .estado(EstadoAssinatura.ativa)
                .acessoAte(somarCiclo(Instant.now(), plano.getCiclo()))
                .build());
    }

    // ------------------------------------------------------------------
    // Renovar e mudar estado
    // ------------------------------------------------------------------

    /**
     * Estende o acesso por mais um ciclo.
     *
     * Conta a partir do vencimento atual, e não de hoje, quando ele ainda não
     * passou: quem renova com cinco dias de antecedência não pode perder esses
     * cinco dias por ter pago adiantado. Se já venceu, conta de hoje — o
     * período em que ficou bloqueado não é devolvido.
     */
    @Transactional
    public Assinatura renovar(UUID assinaturaId) {
        Assinatura a = assinaturas.findById(assinaturaId)
                .orElseThrow(() -> new Recusa("Assinatura não encontrada."));

        Instant agora = Instant.now();
        Instant base = (a.getAcessoAte() != null && a.getAcessoAte().isAfter(agora))
                ? a.getAcessoAte() : agora;

        a.setAcessoAte(somarCiclo(base, a.getPlano().getCiclo()));
        a.setEstado(EstadoAssinatura.ativa);
        a.setCanceladaEm(null);
        return assinaturas.save(a);
    }

    @Transactional
    public Assinatura alterarEstado(UUID assinaturaId, EstadoAssinatura novo) {
        Assinatura a = assinaturas.findById(assinaturaId)
                .orElseThrow(() -> new Recusa("Assinatura não encontrada."));

        a.setEstado(novo);
        a.setCanceladaEm(novo == EstadoAssinatura.cancelada ? Instant.now() : null);
        return assinaturas.save(a);
    }

    /** Um assinante e o que ele contratou — nada, um ou vários sistemas. */
    public record LinhaAdmin(Tenant tenant, List<Assinatura> assinaturas) {}

    /**
     * Lista por ASSINANTE, e não por assinatura.
     *
     * A diferença importa: listar assinaturas esconde quem ainda não contratou
     * nada. O assinante semeado pela V1 — a loja que está em produção — não
     * tem linha em `assinaturas`, e some da tela. Quem administra conclui que
     * ele não existe e cria outro, duplicando a loja.
     */
    @Transactional(readOnly = true)
    public List<LinhaAdmin> listar() {
        List<Assinatura> todas = assinaturas.findAllByOrderByCriadaEmDesc();

        Map<UUID, List<Assinatura>> porTenant = new HashMap<>();
        for (Assinatura a : todas)
            porTenant.computeIfAbsent(a.getTenant().getId(), k -> new ArrayList<>()).add(a);

        return tenants.findAllByOrderByNomeAsc().stream()
                .map(t -> new LinhaAdmin(t, porTenant.getOrDefault(t.getId(), List.of())))
                .toList();
    }

    @Transactional(readOnly = true)
    public Assinatura assinatura(UUID id) {
        return assinaturas.findById(id).orElseThrow(() -> new Recusa("Assinatura não encontrada."));
    }

    /** Provisionamento de cada assinante, indexado pelo id do tenant. */
    @Transactional(readOnly = true)
    public Map<UUID, Provisionamento> provisionamentosPorTenant() {
        Map<UUID, Provisionamento> mapa = new HashMap<>();
        for (Provisionamento p : provisionamentos.findAllByOrderByCriadoEmDesc())
            mapa.putIfAbsent(p.getTenant().getId(), p);
        return mapa;
    }

    @Transactional(readOnly = true)
    public List<Plano> planosDisponiveis() {
        return planos.findByAtivoTrueOrderByProdutoNomeAscPrecoCentavosAsc();
    }

    private static Instant somarCiclo(Instant base, Ciclo ciclo) {
        // Dias, e não meses do calendário: Instant não tem fuso, e somar mês
        // exigiria decidir o que fazer com dia 31. Para cobrança de assinatura
        // o período fixo é mais previsível para os dois lados.
        return ciclo == Ciclo.anual ? base.plus(365, ChronoUnit.DAYS)
                                    : base.plus(30, ChronoUnit.DAYS);
    }

    private static boolean vazio(String s) { return s == null || s.isBlank(); }
}

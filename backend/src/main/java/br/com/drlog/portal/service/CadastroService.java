package br.com.drlog.portal.service;

import br.com.drlog.portal.model.*;
import br.com.drlog.portal.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ============================================================================
 * CADASTRO PELA VITRINE
 *
 * O visitante assina sozinho, por um de dois caminhos: testar grátis
 * deixando um cartão, ou assinar na hora e pagar como puder.
 *
 * Aqui a conta apenas nasce — sem acesso e sem WhatsApp. Quem liga as duas
 * coisas é a LiberacaoService, depois que o Asaas confirma o cartão ou o
 * pagamento. Essa ordem é o ponto inteiro do desenho: provisionar antes da
 * confirmação devolveria o buraco que o cartão veio fechar, porque cada
 * instância na Evolution custa memória real e quem abandona o checkout
 * deixaria a conta dela para trás.
 * ============================================================================
 */
@Service
public class CadastroService {

    private static final Logger log = LoggerFactory.getLogger(CadastroService.class);

    private final ContaRepository contas;
    private final TenantRepository tenants;
    private final ContaTenantRepository vinculos;
    private final AssinaturaRepository assinaturas;
    private final PlanoRepository planos;
    private final RegistroDeRecusas registroDeRecusas;
    private final AdminService adminService;
    private final PasswordEncoder encoder;

    private final int diasDeTeste;
    private final int porIpPorHora;
    private final int tetoDeTestes;

    /**
     * Janela por IP, em memória.
     *
     * Em memória de propósito: é uma trava contra enxurrada, não um controle
     * que precise sobreviver a reinício. Persistir traria complexidade sem
     * mudar o que ela evita.
     */
    private final Map<String, Deque<Instant>> tentativas = new ConcurrentHashMap<>();

    public CadastroService(ContaRepository contas, TenantRepository tenants,
                           ContaTenantRepository vinculos, AssinaturaRepository assinaturas,
                           PlanoRepository planos, RegistroDeRecusas registroDeRecusas,
                           AdminService adminService,
                           PasswordEncoder encoder,
                           @Value("${app.cadastro.dias-de-teste:14}") int diasDeTeste,
                           @Value("${app.cadastro.por-ip-por-hora:3}") int porIpPorHora,
                           @Value("${app.cadastro.teto-de-testes:25}") int tetoDeTestes) {
        this.contas = contas;
        this.tenants = tenants;
        this.vinculos = vinculos;
        this.assinaturas = assinaturas;
        this.planos = planos;
        this.registroDeRecusas = registroDeRecusas;
        this.adminService = adminService;
        this.encoder = encoder;
        this.diasDeTeste = diasDeTeste;
        this.porIpPorHora = porIpPorHora;
        this.tetoDeTestes = tetoDeTestes;
    }

    public int diasDeTeste() { return diasDeTeste; }

    /** O que o cadastro devolve: a conta criada e o assinante. */
    public record Resultado(Conta conta, Tenant tenant, Assinatura assinatura) {}

    /**
     * Os dois caminhos da vitrine.
     *
     * A diferença entre eles não é forma de pagamento — é o teste. O cartão só
     * é exigido em TESTE porque é o que torna possível prometer dias grátis
     * sem abrir a porta para conta inventada; quem escolhe AGORA já está
     * pagando, e aí Pix e boleto voltam a valer.
     */
    public enum Modo { TESTE, AGORA }

    @Transactional
    public Resultado cadastrar(String nomeLoja, UUID planoId, String nomeResponsavel,
                               String email, String senha, Modo modo, String documento,
                               String ip) {

        if (vazio(nomeLoja))        throw new AdminService.Recusa("Informe o nome da loja.");
        if (vazio(nomeResponsavel)) throw new AdminService.Recusa("Informe o seu nome.");
        if (vazio(email))           throw new AdminService.Recusa("Informe o e-mail.");
        if (senha == null || senha.length() < 8)
            throw new AdminService.Recusa("A senha precisa ter pelo menos 8 caracteres.");
        if (planoId == null)        throw new AdminService.Recusa("Escolha um plano.");
        if (modo == null)           throw new AdminService.Recusa("Escolha como quer começar.");

        // Só quem vai pagar agora precisa do documento. Exigir CNPJ de quem só
        // queria experimentar afasta antes de a pessoa ver o sistema.
        if (modo == Modo.AGORA && (documento == null || documento.replaceAll("\\D", "").length() < 11))
            throw new AdminService.Recusa("Informe um CNPJ ou CPF válido — o Asaas exige para cobrar.");

        // A mensagem é a mesma de e-mail repetido de propósito: dizer "esta
        // conta já existe" a quem não a possui revela quem é cliente.
        if (contas.buscarPorEmail(email).isPresent()) {
            registrarRecusa(ip, email, "e-mail já cadastrado");
            throw new AdminService.Recusa(
                "Não foi possível criar a conta com esse e-mail. "
              + "Se já é seu, entre pelo login.");
        }

        travarPorIp(ip, email);
        travarPorCapacidade(ip, email);

        Plano plano = planos.findById(planoId)
                .orElseThrow(() -> new AdminService.Recusa("Plano não encontrado."));

        Tenant tenant = tenants.save(Tenant.builder()
                .codigo(adminService.codigoDisponivelPara(nomeLoja))
                .nome(nomeLoja.trim())
                .documento(documento == null || documento.isBlank() ? null : documento.trim())
                .build());

        Conta conta = contas.save(Conta.builder()
                .email(email.trim())
                .senhaHash(encoder.encode(senha))
                .nome(nomeResponsavel.trim())
                .ativa(true).admin(false)
                .build());

        vinculos.save(ContaTenant.builder()
                .conta(conta).tenant(tenant).papel(Papel.dono).build());

        // Nasce sem acesso e sem data. O acesso é ligado pela LiberacaoService
        // quando o Asaas confirmar o cartão ou o pagamento — nunca antes.
        Assinatura assinatura = assinaturas.save(Assinatura.builder()
                .tenant(tenant)
                .produto(plano.getProduto())
                .plano(plano)
                .estado(EstadoAssinatura.aguardando_pagamento)
                .origem("autocadastro")
                .build());

        log.info("Nova conta pela vitrine: {} ({}), modo {} — aguardando o Asaas",
                 tenant.getNome(), tenant.getCodigo(), modo);
        return new Resultado(conta, tenant, assinatura);
    }

    // ------------------------------------------------------------------
    // Travas
    // ------------------------------------------------------------------

    /**
     * Enxurrada vinda do mesmo lugar.
     *
     * Não impede um atacante decidido, que troca de IP. Impede o caso comum —
     * script ingênuo, clique repetido, teste de formulário — que é o que
     * realmente derrubaria o servidor por acidente.
     */
    private void travarPorIp(String ip, String email) {
        if (ip == null || ip.isBlank()) return;
        Instant agora = Instant.now();
        Deque<Instant> janela = tentativas.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (janela) {
            janela.removeIf(t -> t.isBefore(agora.minus(Duration.ofHours(1))));
            if (janela.size() >= porIpPorHora) {
                registrarRecusa(ip, email, "limite de %d cadastros por hora no mesmo endereço"
                        .formatted(porIpPorHora));
                throw new AdminService.Recusa(
                    "Muitos cadastros deste mesmo lugar na última hora. "
                  + "Tente mais tarde ou fale com a gente no WhatsApp.");
            }
            janela.addLast(agora);
        }
    }

    /**
     * Teto de testes simultâneos.
     *
     * Cada teste provisiona uma instância de WhatsApp, e instância de WhatsApp
     * custa memória real no servidor. Sem teto, um pico de cadastros derruba
     * quem já é cliente pagante — o oposto do que a plataforma deve proteger.
     *
     * Barrar quem chega é ruim; derrubar quem paga é pior.
     *
     * Conta apenas quem está em teste de verdade. Quem parou no checkout não
     * entra na conta porque não consome instância nenhuma — de modo que o teto
     * agora mede memória comprometida, e não curiosidade.
     */
    private void travarPorCapacidade(String ip, String email) {
        long emTeste = assinaturas.countByEstado(EstadoAssinatura.trial);
        if (emTeste >= tetoDeTestes) {
            registrarRecusa(ip, email, "teto de %d testes simultâneos atingido".formatted(tetoDeTestes));
            log.warn("Teto de testes atingido ({}). Cadastro recusado.", tetoDeTestes);
            throw new AdminService.Recusa(
                "Estamos com a fila de testes cheia neste momento. "
              + "Fale com a gente no WhatsApp que liberamos o seu.");
        }
    }

    /**
     * Delegado a um componente próprio, com transação separada: a recusa é
     * lançada como exceção logo em seguida, e a exceção desfaria a transação
     * em curso — levando junto o registro.
     */
    private void registrarRecusa(String ip, String email, String motivo) {
        registroDeRecusas.registrar(ip, email, motivo);
    }

    private static boolean vazio(String s) { return s == null || s.isBlank(); }
}

package br.com.drlog.portal.config;

import br.com.drlog.portal.model.*;
import br.com.drlog.portal.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * ============================================================================
 * PRIMEIRO ACESSO
 *
 * Um banco recém-migrado tem tenants e produtos (vindos da V1), mas nenhuma
 * conta — e sem conta não há como entrar para criar a primeira. Este runner
 * fecha esse círculo.
 *
 * Só age quando não existe nenhuma conta. Depois disso é inerte, e não há
 * caminho para ele recriar ou sobrescrever o que já existe.
 * ============================================================================
 */
@Configuration
public class BootstrapConta {

    private static final Logger log = LoggerFactory.getLogger(BootstrapConta.class);

    /** O assinante semeado pela V1, que corresponde à loja já em produção. */
    private static final String TENANT_INICIAL = "styllos";

    @Value("${app.admin.email:}")
    private String emailConfigurado;

    @Value("${app.admin.senha:}")
    private String senhaConfigurada;

    @Bean
    ApplicationRunner criarPrimeiraConta(ContaRepository contas,
                                         TenantRepository tenants,
                                         ContaTenantRepository vinculos,
                                         PasswordEncoder encoder) {
        return args -> executar(contas, tenants, vinculos, encoder);
    }

    @Transactional
    void executar(ContaRepository contas, TenantRepository tenants,
                  ContaTenantRepository vinculos, PasswordEncoder encoder) {

        if (contas.count() > 0) return;

        String email = (emailConfigurado == null || emailConfigurado.isBlank())
                ? "admin@drlog.com.br"
                : emailConfigurado.trim();

        String senha = senhaConfigurada;
        boolean gerada = false;

        // Mesma postura do Styllus: nenhuma senha padrão. Um valor previsível
        // numa aplicação publicada na internet equivale a não ter proteção, e
        // ninguém troca o que já funciona. A senha aleatória incomoda o
        // suficiente para ser configurada, sem impedir o desenvolvimento local.
        if (senha == null || senha.isBlank()) {
            senha = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            gerada = true;
        }

        Conta conta = contas.save(Conta.builder()
                .email(email)
                .senhaHash(encoder.encode(senha))
                .nome("Administrador")
                .ativa(true)
                .build());

        // Vincula ao assinante que já existe, para que o primeiro login tenha
        // o que mostrar em vez de um painel vazio sem explicação.
        tenants.findByCodigo(TENANT_INICIAL).ifPresent(tenant ->
                vinculos.save(ContaTenant.builder()
                        .conta(conta)
                        .tenant(tenant)
                        .papel(Papel.dono)
                        .build()));

        if (gerada) {
            log.warn("""
                
                =================================================================
                 PRIMEIRO ACESSO — APP_ADMIN_SENHA não estava configurada.
                 Foi criada uma conta com senha aleatória:
                
                     e-mail: {}
                     senha:  {}
                
                 Esta senha NÃO se repete: ela já está gravada no banco. Anote-a
                 agora, ou apague a conta e reinicie para gerar outra.
                =================================================================
                """, email, senha);
        } else {
            log.info("Primeira conta criada a partir de APP_ADMIN_EMAIL: {}", email);
        }
    }
}

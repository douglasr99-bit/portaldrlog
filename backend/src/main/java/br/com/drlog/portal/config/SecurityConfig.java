package br.com.drlog.portal.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * ============================================================================
 * SEGURANÇA DO PORTAL
 *
 * Sessão por cookie e formulário renderizado no servidor. O Thymeleaf insere o
 * campo _csrf em todo form com th:action, então não é preciso o vaivém de
 * token por cookie e cabeçalho que o Styllus usa — ali o formulário é enviado
 * por fetch, aqui não.
 * ============================================================================
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Com um UserDetailsService (ContaService) e um PasswordEncoder no
     * contexto, o Spring monta o DaoAuthenticationProvider sozinho. Declará-lo
     * à mão daria o mesmo resultado e um aviso a cada inicialização.
     *
     * Os dois comportamentos que importam já vêm ligados por padrão: "conta
     * não existe" e "senha errada" chegam como a mesma BadCredentialsException,
     * e o provider compara um hash falso quando o usuário não existe, para que
     * o tempo de resposta não denuncie quais e-mails estão cadastrados.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(rotas -> rotas
                // A vitrine é pública: é a porta da frente da empresa, e
                // quem chega nela ainda não tem conta nenhuma.
                .requestMatchers("/", "/sistemas/**").permitAll()
                // "/error" é o caminho real do Spring, não "/erro". Sem liberá-lo,
                // qualquer erro numa página pública — um endereço de sistema
                // que não existe, por exemplo — redireciona o visitante para a
                // tela de login, na porta de entrada da empresa.
                .requestMatchers("/css/**", "/js/**", "/img/**", "/favicon.ico", "/favicon.svg", "/apple-touch-icon.png", "/error").permitAll()
                .requestMatchers("/login").permitAll()
                // Reservado para a etapa 2: a chave pública que os sistemas
                // vendidos usam para verificar o token. É público por
                // definição — é a chave pública.
                .requestMatchers("/.well-known/**").permitAll()
                .anyRequest().authenticated()
            )
            .formLogin(login -> login
                .loginPage("/login")
                .loginProcessingUrl("/login")
                .usernameParameter("email")
                .passwordParameter("senha")
                // `true` força o destino mesmo quando havia uma página salva.
                // Sem isso, quem foi barrado num link antigo volta para ele
                // depois de entrar, e o link pode não existir mais.
                .defaultSuccessUrl("/painel", true)
                .failureUrl("/login?erro")
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/")
                .deleteCookies("JSESSIONID")
                .invalidateHttpSession(true)
            )
            .sessionManagement(sessao -> sessao
                // Troca o identificador de sessão na autenticação. Sem isso,
                // um identificador obtido antes do login continua válido
                // depois dele (fixação de sessão).
                .sessionFixation(fix -> fix.changeSessionId())
            );

        return http.build();
    }
}

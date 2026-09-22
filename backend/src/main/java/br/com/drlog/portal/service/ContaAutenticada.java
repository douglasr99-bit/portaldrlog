package br.com.drlog.portal.service;

import br.com.drlog.portal.model.Conta;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * A conta logada, do ponto de vista do Spring Security.
 *
 * Carrega o id junto: sem ele, todo controller teria que buscar a conta pelo
 * e-mail de novo a cada requisição só para saber de quem são os dados.
 */
public class ContaAutenticada implements UserDetails {

    private final UUID id;
    private final String email;
    private final String nome;
    private final String senhaHash;
    private final boolean ativa;
    private final boolean admin;

    public ContaAutenticada(Conta conta) {
        this.id = conta.getId();
        this.email = conta.getEmail();
        this.nome = conta.getNome();
        this.senhaHash = conta.getSenhaHash();
        this.ativa = conta.isAtiva();
        this.admin = conta.isAdmin();
    }

    public UUID getId() { return id; }
    public String getNome() { return nome; }
    public String getEmail() { return email; }

    public boolean isAdmin() { return admin; }

    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return admin
                ? List.of(new SimpleGrantedAuthority("ROLE_ASSINANTE"),
                          new SimpleGrantedAuthority("ROLE_ADMIN"))
                : List.of(new SimpleGrantedAuthority("ROLE_ASSINANTE"));
    }
    @Override public String getPassword() { return senhaHash; }
    @Override public String getUsername() { return email; }
    @Override public boolean isEnabled() { return ativa; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
}

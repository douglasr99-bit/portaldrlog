package br.com.drlog.portal.service;

import br.com.drlog.portal.repository.ContaRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class ContaService implements UserDetailsService {

    private final ContaRepository contas;

    public ContaService(ContaRepository contas) {
        this.contas = contas;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return contas.buscarPorEmail(email)
                .map(ContaAutenticada::new)
                // A mensagem é genérica de propósito: distinguir "conta não
                // existe" de "senha errada" entrega ao atacante a lista de
                // e-mails cadastrados na plataforma.
                .orElseThrow(() -> new UsernameNotFoundException("Credenciais inválidas"));
    }
}

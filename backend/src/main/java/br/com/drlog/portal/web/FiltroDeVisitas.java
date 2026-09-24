package br.com.drlog.portal.web;

import br.com.drlog.portal.service.RegistroDeVisitas;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * ============================================================================
 * QUEM PASSOU POR AQUI
 *
 * Conta as páginas da vitrine e a entrada do cadastro. Só isso: o objetivo é a
 * forma do funil — chegou, abriu o cadastro, criou conta, pagou —, não o
 * rastro de navegação de ninguém.
 * ============================================================================
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class FiltroDeVisitas extends OncePerRequestFilter {

    private final RegistroDeVisitas registro;

    public FiltroDeVisitas(RegistroDeVisitas registro) {
        this.registro = registro;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp,
                                    FilterChain chain) throws ServletException, IOException {
        // A contagem vem depois da resposta: se a página falhar, não houve
        // visita a contar, e o código de status é o que diz isso.
        try {
            chain.doFilter(req, resp);
        } finally {
            String caminho = classificar(req);
            if (caminho != null && resp.getStatus() < 400)
                registro.registrar(caminho, enderecoDe(req), req.getHeader("User-Agent"),
                                   pareceRobo(req.getHeader("User-Agent")),
                                   req.getHeader("Referer"), req.getParameter("utm_source"),
                                   req.getServerName());
        }
    }

    /**
     * Reduz a URL a um punhado de rótulos conhecidos.
     *
     * Guardar o caminho cru deixaria a cardinalidade solta: qualquer varredura
     * de URLs inexistentes criaria milhares de linhas, e a tabela de medição
     * viraria alvo de quem quisesse enchê-la. Rótulo desconhecido não é
     * contado.
     */
    private String classificar(HttpServletRequest req) {
        if (!"GET".equals(req.getMethod())) return null;
        String p = req.getRequestURI();
        if (p == null) return null;

        if (p.equals("/") || p.isEmpty())    return "vitrine";
        if (p.startsWith("/sistemas/"))      return "produto";
        if (p.startsWith("/assinar/retorno"))return null;   // volta do gateway, não é visita
        if (p.startsWith("/assinar/"))       return "cadastro";
        if (p.equals("/login"))              return "login";
        if (p.equals("/painel"))             return "painel";
        return null;
    }

    private static boolean pareceRobo(String ua) {
        if (ua == null || ua.isBlank()) return true;
        String u = ua.toLowerCase();
        return u.contains("bot") || u.contains("crawler") || u.contains("spider")
            || u.contains("curl") || u.contains("wget") || u.contains("python")
            || u.contains("headless") || u.contains("monitor") || u.contains("uptime");
    }

    /** O endereço de quem chamou, atravessando o proxy — como na trava de cadastro. */
    private static String enderecoDe(HttpServletRequest r) {
        String encaminhado = r.getHeader("X-Forwarded-For");
        if (encaminhado != null && !encaminhado.isBlank())
            return encaminhado.split(",")[0].trim();
        return r.getRemoteAddr();
    }
}

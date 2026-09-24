package br.com.drlog.portal.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;

/**
 * ============================================================================
 * CONTAGEM DE VISITAS
 *
 * Responde à pergunta que o resto do banco não respondia: de quantas pessoas
 * vieram os cadastros. Sem o topo do funil, poucos cadastros podem significar
 * "ninguém chegou" ou "chegaram e desistiram" — e as duas pedem ações opostas.
 *
 * Fora da linha da requisição, por @Async: contar visita não pode atrasar a
 * página, e uma falha aqui jamais pode derrubar a vitrine. Por isso todo o
 * corpo está em try/catch.
 * ============================================================================
 */
@Service
public class RegistroDeVisitas {

    private static final Logger log = LoggerFactory.getLogger(RegistroDeVisitas.class);
    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");

    private final JdbcTemplate jdbc;

    /** Lido uma vez e guardado: é fixo, e ir ao banco a cada visita seria desperdício. */
    private volatile String sal;

    private static final int TETO_DE_ORIGENS = 40;
    private final java.util.Set<String> conhecidas = new java.util.HashSet<>();
    private LocalDate diaDasConhecidas;

    public RegistroDeVisitas(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Async
    public void registrar(String caminho, String ip, String navegador, boolean robo,
                          String referencia, String campanha, String meuHost) {
        try {
            LocalDate dia = LocalDate.now(FUSO);

            jdbc.update("""
                    insert into visitas (dia, caminho, robo, visualizacoes)
                    values (?, ?, ?, 1)
                    on conflict (dia, caminho, robo)
                    do update set visualizacoes = visitas.visualizacoes + 1
                    """, dia, caminho, robo);

            // Robô não entra na conta de visitantes: inflaria o denominador do
            // funil e faria a taxa de conversão parecer pior do que é.
            if (!robo) {
                int novo = jdbc.update("""
                        insert into visitantes (dia, marca) values (?, ?)
                        on conflict do nothing
                        """, dia, marcaDe(dia, ip, navegador));

                // A origem é contada uma vez por visitante, no momento em que
                // ele aparece pela primeira vez no dia. Contar a cada página
                // diria de onde veio cada clique — e como a pessoa navega
                // dentro do próprio site, quase tudo seria "interno".
                if (novo == 1) {
                    jdbc.update("""
                            insert into origens (dia, origem, visitantes)
                            values (?, ?, 1)
                            on conflict (dia, origem)
                            do update set visitantes = origens.visitantes + 1
                            """, dia, origemDe(dia, referencia, campanha, meuHost));
                }
            }
        } catch (Exception e) {
            // Nunca propaga. Medição que derruba a página mede o quê?
            log.debug("Não consegui registrar a visita a {}: {}", caminho, e.toString());
        }
    }

    /**
     * De onde a pessoa veio.
     *
     * A campanha (`utm_source`) vem antes do `referer` de propósito: o
     * navegador interno do Instagram costuma não enviar `referer`, e é
     * justamente essa origem que mais interessa medir. Sem a campanha, o
     * tráfego do Instagram apareceria como "direto" e a divulgação pareceria
     * não estar funcionando.
     */
    private String origemDe(LocalDate dia, String referencia, String campanha, String meuHost) {
        String bruta = (campanha != null && !campanha.isBlank())
                ? campanha
                : classificar(referencia, meuHost);
        return limitada(dia, normalizar(bruta));
    }

    private static String classificar(String referencia, String meuHost) {
        if (referencia == null || referencia.isBlank()) return "direto";
        String h;
        try {
            h = java.net.URI.create(referencia).getHost();
        } catch (Exception e) {
            return "outro";
        }
        if (h == null) return "outro";
        h = h.toLowerCase();

        if (meuHost != null && h.equalsIgnoreCase(meuHost)) return "interno";
        if (h.contains("instagram"))                        return "instagram";
        if (h.contains("facebook") || h.startsWith("fb."))  return "facebook";
        if (h.contains("whatsapp") || h.contains("wa.me"))  return "whatsapp";
        if (h.contains("google"))                           return "google";
        if (h.contains("bing"))                             return "bing";
        if (h.contains("duckduckgo"))                       return "duckduckgo";
        if (h.contains("youtube") || h.contains("youtu.be"))return "youtube";
        if (h.contains("linkedin"))                         return "linkedin";
        if (h.contains("tiktok"))                           return "tiktok";
        return h.startsWith("www.") ? h.substring(4) : h;
    }

    private static String normalizar(String s) {
        String limpo = s.toLowerCase().replaceAll("[^a-z0-9._-]", "");
        if (limpo.isBlank()) return "outro";
        return limpo.length() > 40 ? limpo.substring(0, 40) : limpo;
    }

    /**
     * Teto de origens distintas por dia.
     *
     * `utm_source` vem da URL, ou seja, de quem chega. Sem teto, alguém
     * gerando valores aleatórios encheria a tabela de medição — e o custo
     * seria nosso. Passando do teto, o excedente vira "outro": perde-se
     * detalhe, não se perde a contagem.
     */
    private String limitada(LocalDate dia, String origem) {
        synchronized (conhecidas) {
            if (!dia.equals(diaDasConhecidas)) {
                conhecidas.clear();
                diaDasConhecidas = dia;
            }
            if (conhecidas.contains(origem)) return origem;
            if (conhecidas.size() >= TETO_DE_ORIGENS) return "outro";
            conhecidas.add(origem);
            return origem;
        }
    }

    /**
     * Uma marca que conta sem identificar.
     *
     * O dia entra no hash de propósito: a marca perde o sentido amanhã, então
     * ela serve para contar quantas pessoas diferentes vieram hoje e não para
     * reconhecer alguém que voltou. O sal impede a força bruta sobre a faixa
     * de IPv4, que sem ele levaria minutos.
     */
    private String marcaDe(LocalDate dia, String ip, String navegador) throws Exception {
        String cru = dia + "|" + sal() + "|" + (ip == null ? "" : ip)
                   + "|" + (navegador == null ? "" : navegador);
        byte[] resumo = MessageDigest.getInstance("SHA-256")
                .digest(cru.getBytes(StandardCharsets.UTF_8));
        // 16 bytes bastam: a chance de dois visitantes colidirem no mesmo dia é
        // desprezível, e guardar menos é guardar menos.
        return HexFormat.of().formatHex(resumo, 0, 16);
    }

    private String sal() {
        if (sal == null) {
            synchronized (this) {
                if (sal == null)
                    sal = jdbc.queryForObject("select sal from medicao_sal", String.class);
            }
        }
        return sal;
    }
}

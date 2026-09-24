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

    public RegistroDeVisitas(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Async
    public void registrar(String caminho, String ip, String navegador, boolean robo) {
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
                jdbc.update("""
                        insert into visitantes (dia, marca) values (?, ?)
                        on conflict do nothing
                        """, dia, marcaDe(dia, ip, navegador));
            }
        } catch (Exception e) {
            // Nunca propaga. Medição que derruba a página mede o quê?
            log.debug("Não consegui registrar a visita a {}: {}", caminho, e.toString());
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

package br.com.drlog.portal.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * ============================================================================
 * O FUNIL, DIA A DIA
 *
 * Cada linha responde a uma pergunta diferente, e é a comparação entre elas
 * que diz onde agir:
 *
 *   visitantes sem cadastros   → a página não convence, ou o público é errado
 *   cadastros sem contas       → o formulário ou o cartão estão espantando
 *   contas sem ativas          → o produto não segurou no teste
 *   nada em lugar nenhum       → não é conversão, é falta de tráfego
 * ============================================================================
 */
@Service
public class MedicaoService {

    private final JdbcTemplate jdbc;

    public MedicaoService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Um dia do funil.
     *
     * `cadastros` é quem ABRIU a tela de cadastro; `contas` é quem a
     * preencheu. A distância entre os dois é o custo do formulário — incluindo
     * a exigência do cartão, que é a decisão mais discutível do desenho e a
     * que mais merece um número em cima.
     */
    public record Dia(LocalDate dia, long visitantes, long vitrine, long cadastros,
                      long contas, long ativas, long robos) {}

    /**
     * Os dias com movimento, dentro da janela.
     *
     * Dia parado não vira linha. Com o site novo, mostrar quatorze linhas de
     * zeros empurraria a lista de assinantes para fora da tela — andaime vazio
     * ocupando o lugar do conteúdo. Hoje aparece sempre, mesmo zerado, porque
     * "ainda não veio ninguém hoje" é informação.
     */
    @Transactional(readOnly = true)
    public List<Dia> diasComMovimento(int janela) {
        List<Dia> todos = ultimosDias(janela);
        LocalDate hoje = LocalDate.now(java.time.ZoneId.of("America/Sao_Paulo"));
        return todos.stream()
                .filter(d -> d.dia().equals(hoje) || d.visitantes() > 0 || d.contas() > 0
                          || d.vitrine() > 0 || d.cadastros() > 0 || d.robos() > 0)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Dia> ultimosDias(int quantos) {
        return jdbc.query("""
            with dias as (
                select generate_series(current_date - (? - 1) * interval '1 day',
                                       current_date, interval '1 day')::date as dia
            )
            select d.dia,
                   coalesce((select count(*) from visitantes v where v.dia = d.dia), 0)            as visitantes,
                   coalesce((select sum(visualizacoes) from visitas x
                              where x.dia = d.dia and x.caminho = 'vitrine'  and not x.robo), 0)   as vitrine,
                   coalesce((select sum(visualizacoes) from visitas x
                              where x.dia = d.dia and x.caminho = 'cadastro' and not x.robo), 0)   as cadastros,
                   coalesce((select count(*) from assinaturas a
                              where a.origem = 'autocadastro'
                                and (a.criada_em at time zone 'America/Sao_Paulo')::date = d.dia), 0) as contas,
                   coalesce((select count(*) from assinaturas a
                              where a.origem = 'autocadastro' and a.estado = 'ativa'
                                and (a.criada_em at time zone 'America/Sao_Paulo')::date = d.dia), 0) as ativas,
                   coalesce((select sum(visualizacoes) from visitas x
                              where x.dia = d.dia and x.robo), 0)                                  as robos
            from dias d
            order by d.dia desc
            """,
            (rs, n) -> new Dia(rs.getObject("dia", LocalDate.class),
                               rs.getLong("visitantes"), rs.getLong("vitrine"),
                               rs.getLong("cadastros"), rs.getLong("contas"),
                               rs.getLong("ativas"), rs.getLong("robos")),
            quantos);
    }

    /** Uma origem e quantos visitantes vieram dela na janela. */
    public record Origem(String origem, long visitantes) {}

    /**
     * De onde vieram, somando a janela inteira.
     *
     * Somado, e não dia a dia, porque a pergunta é para onde direcionar
     * esforço — e um dia isolado de uma origem nova não responde isso.
     */
    /**
     * As principais, com o resto somado numa linha só.
     *
     * A cauda longa não informa: saber que uma origem trouxe um visitante não
     * muda decisão nenhuma, e quarenta linhas dessas empurram a lista de
     * assinantes para fora da tela — o mesmo erro que a tabela de dias já
     * tinha cometido.
     */
    @Transactional(readOnly = true)
    public List<Origem> principais(int janela, int quantas) {
        List<Origem> todas = origens(janela);
        if (todas.size() <= quantas) return todas;

        List<Origem> topo = new java.util.ArrayList<>(todas.subList(0, quantas));
        long resto = todas.subList(quantas, todas.size()).stream()
                .mapToLong(Origem::visitantes).sum();
        topo.add(new Origem("+ %d origens menores".formatted(todas.size() - quantas), resto));
        return topo;
    }

    @Transactional(readOnly = true)
    public List<Origem> origens(int janela) {
        return jdbc.query("""
            select origem, sum(visitantes) as visitantes
              from origens
             where dia > current_date - ? * interval '1 day'
             group by origem
             order by 2 desc, 1
            """,
            (rs, n) -> new Origem(rs.getString("origem"), rs.getLong("visitantes")),
            janela);
    }

    /** Se já existe qualquer medição — antes disso a tabela na tela só confundiria. */
    @Transactional(readOnly = true)
    public boolean temDados() {
        Long n = jdbc.queryForObject("select count(*) from visitas", Long.class);
        return n != null && n > 0;
    }
}

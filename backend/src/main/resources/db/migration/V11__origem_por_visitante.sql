-- ============================================================================
-- V11 — Uma origem por visitante NÃO bastava
--
-- A V10 registrava a origem só na primeira aparição do visitante no dia. O
-- efeito: quem já tinha entrado no site naquele dia e depois voltava por um
-- link de campanha não era contado em lugar nenhum — o clique sumia.
--
-- Para um site cujo propósito é medir se a divulgação funciona, isso inverte
-- o resultado no pior momento: quanto mais alguém acompanha a marca, menos
-- suas chegadas aparecem.
--
-- Agora cada par visitante+origem conta uma vez por dia. Quem chegou por dois
-- caminhos aparece nos dois — e por isso a soma das origens pode passar do
-- número de visitantes distintos. É de propósito, e a tela diz isso.
-- ============================================================================

create table visitante_origem (
    dia    date not null,
    marca  text not null,
    origem text not null,
    primary key (dia, marca, origem)
);

-- ----------------------------------------------------------------------------
-- `ig` e `instagram` eram a mesma coisa em duas linhas
--
-- Duas linhas para a mesma origem não dividem só o número: dividem a
-- comparação, que é o único motivo de a tabela existir.
-- ----------------------------------------------------------------------------
insert into origens (dia, origem, visitantes)
select dia, 'instagram', visitantes from origens where origem = 'ig'
on conflict (dia, origem) do update
    set visitantes = origens.visitantes + excluded.visitantes;

delete from origens where origem = 'ig';

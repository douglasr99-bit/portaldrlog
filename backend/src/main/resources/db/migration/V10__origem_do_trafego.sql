-- ============================================================================
-- V10 — De onde vieram
--
-- Saber quantos chegaram não diz o que fazer. Saber de onde diz: se o
-- Instagram traz gente e busca não traz, o esforço vai para um lado; se o
-- tráfego é quase todo direto, alguém está divulgando o endereço na mão e é
-- isso que está funcionando.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- Uma linha por origem por dia
--
-- Contada UMA VEZ POR VISITANTE, no momento em que ele aparece pela primeira
-- vez no dia — e não a cada página. Contar por página diria de onde veio cada
-- clique, e como o cliente navega dentro do próprio site, quase tudo seria
-- "interno": a pergunta "de onde vem minha audiência" ficaria sem resposta.
-- ----------------------------------------------------------------------------
create table origens (
    dia      date   not null,
    origem   text   not null,
    visitantes bigint not null default 0,
    primary key (dia, origem)
);

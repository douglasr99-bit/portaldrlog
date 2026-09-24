-- ============================================================================
-- V9 — Contagem de visitas
--
-- Até aqui o Portal sabia quantas lojas assinaram e não sabia de quantas
-- pessoas. Sem o topo do funil, um número baixo de cadastros não distingue
-- "ninguém está chegando" de "estão chegando e desistindo" — e essas duas
-- situações pedem ações opostas.
--
-- Contado no servidor, sem script de terceiro e sem cookie. A página inteira
-- da vitrine existe para parecer confiável e não-genérica; encher de
-- rastreador contradiz isso, e ainda custaria memória de uma VPS que já está
-- no limite sustentando as instâncias de WhatsApp — que são o que gera
-- receita.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- O sal da marca de visitante
--
-- A marca é um hash de endereço + navegador + dia. O sal impede que alguém com
-- acesso ao banco descubra o endereço por força bruta: sem ele, varrer a faixa
-- de IPv4 contra um hash conhecido é trabalho de minutos.
--
-- Uma linha só, criada aqui com valor aleatório. Se o sal mudar, os
-- visitantes do dia passam a contar dobrado — por isso ele nasce no banco e
-- não em variável de ambiente, onde um deploy descuidado o trocaria.
-- ----------------------------------------------------------------------------
create table medicao_sal (
    unica  boolean primary key default true,
    sal    text    not null default gen_random_uuid()::text,
    constraint ck_medicao_uma_linha check (unica)
);
insert into medicao_sal default values;

-- ----------------------------------------------------------------------------
-- Páginas vistas, agregadas
--
-- Agregado por (dia, caminho), e não uma linha por acesso: o que se quer saber
-- é a forma do funil, não a sequência de cliques de ninguém. Uma linha por
-- acesso guardaria muito mais sobre as pessoas e responderia às mesmas
-- perguntas.
--
-- `robo` separa em vez de descartar. Descartar silenciosamente faria o número
-- parecer mais limpo do que é, e a detecção por User-Agent acerta o robô
-- educado e erra o disfarçado.
-- ----------------------------------------------------------------------------
create table visitas (
    dia           date   not null,
    caminho       text   not null,
    robo          boolean not null default false,
    visualizacoes bigint not null default 0,
    primary key (dia, caminho, robo)
);

-- ----------------------------------------------------------------------------
-- Visitantes distintos do dia
--
-- A marca some do sentido no dia seguinte, porque o dia entra no hash. Isso é
-- proposital: serve para contar quantas pessoas diferentes vieram hoje, e não
-- para reconhecer alguém que voltou na semana passada.
-- ----------------------------------------------------------------------------
create table visitantes (
    dia    date not null,
    marca  text not null,
    primary key (dia, marca)
);

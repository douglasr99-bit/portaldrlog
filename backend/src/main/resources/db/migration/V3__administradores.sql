-- ============================================================================
-- V3 — Contas administradoras
--
-- Até aqui toda conta era igual. A tela de administração cria assinantes,
-- renova e suspende: precisa de uma distinção entre quem opera a plataforma e
-- quem assina um sistema dela.
-- ============================================================================

alter table contas
    -- Falso por padrão, de propósito: conta nova nasce sem poder nenhum
    -- sobre a plataforma. Conceder é ato explícito.
    add column admin boolean not null default false;

-- A conta criada pelo primeiro acesso é a única que existia antes desta
-- migração e a única que tem como administrar. Sem isto, ninguém consegue
-- entrar em /admin e não há caminho para conceder o acesso a ninguém.
update contas
   set admin = true
 where id = (select id from contas order by criada_em asc limit 1);

create index idx_conta_admin on contas (admin) where admin;

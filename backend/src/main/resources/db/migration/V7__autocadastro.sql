-- ============================================================================
-- V7 — Cadastro pela vitrine
--
-- O visitante passa a assinar sozinho: cria a conta, ganha 7 dias de teste
-- com tudo, e converte pagando — sem ninguém do outro lado.
-- ============================================================================

alter table assinaturas
    -- De onde veio: 'administracao' ou 'autocadastro'.
    --
    -- Importa porque as duas origens merecem desconfianças diferentes. Quem
    -- foi criado na administração passou por uma conversa; quem se cadastrou
    -- sozinho, não — e é na segunda que abuso aparece.
    add column origem text not null default 'administracao';

create index idx_assinatura_trial on assinaturas (estado, criada_em)
    where estado = 'trial';


-- ----------------------------------------------------------------------------
-- cadastros_recusados — tentativas barradas pelas travas
--
-- Cadastro aberto com provisionamento automático de WhatsApp é um caminho
-- para esgotar a memória do servidor: cada conta criada consome uma instância
-- na Evolution. As travas existem por isso, e o registro existe para que
-- barrar demais seja perceptível — uma trava apertada que rejeita cliente de
-- verdade é pior que o abuso que ela evita.
-- ----------------------------------------------------------------------------
create table cadastros_recusados (
    id         uuid        primary key default gen_random_uuid(),
    ip         text,
    email      text,
    motivo     text        not null,
    criado_em  timestamptz not null default now()
);

create index idx_recusado_ip on cadastros_recusados (ip, criado_em desc);

-- ============================================================================
-- V5 — Instância de WhatsApp por assinante
--
-- O Portal é quem fala com a Evolution usando a chave global. Cada sistema
-- vendido recebe apenas o token da instância do seu assinante.
--
-- A diferença é o que acontece quando o roteamento de tenant erra: com token
-- por instância, a Evolution responde 401 e a mensagem não sai. Com a chave
-- global espalhada pelos sistemas, ela sai — pelo WhatsApp da loja errada,
-- para o cliente da loja errada, e sem desfazer.
-- ============================================================================

create table provisionamentos (
    id            uuid        primary key default gen_random_uuid(),
    tenant_id     uuid        not null references tenants (id)  on delete cascade,
    produto_id    uuid        not null references produtos (id),

    -- Nome da instância na Evolution. Legível de propósito: quem for olhar a
    -- Evolution precisa conseguir dizer de qual loja é cada instância.
    instancia     text        not null,

    -- A credencial daquela instância, devolvida pela Evolution na criação.
    -- Só sai daqui pelo canal servidor-a-servidor; nunca pelo token que passa
    -- no navegador.
    token         text,

    estado        text        not null default 'pendente',
    erro          text,
    criado_em     timestamptz not null default now(),
    atualizado_em timestamptz not null default now(),

    constraint uk_provisionamento unique (tenant_id, produto_id),
    constraint uk_provisionamento_instancia unique (instancia),
    constraint ck_provisionamento_estado check (estado in ('pendente', 'ativo', 'falhou'))
);

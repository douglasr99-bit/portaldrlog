-- ============================================================================
-- V1 — Contas, assinantes e catálogo
--
-- Escopo da etapa 1 da sequência descrita em directives/arquitetura_portal.md:
-- quem faz login, de quem são os dados, e o que está à venda. As tabelas de
-- cobrança (cobrancas, eventos_gateway) e de provisionamento chegam nas
-- migrações das etapas 3 e 4, junto com as funcionalidades que as usam.
-- ============================================================================


-- ----------------------------------------------------------------------------
-- contas — a pessoa que faz login
-- ----------------------------------------------------------------------------
create table contas (
    id          uuid        primary key default gen_random_uuid(),
    email       text        not null,
    senha_hash  text        not null,
    nome        text        not null,
    ativa       boolean     not null default true,
    criada_em   timestamptz not null default now()
);

-- Unicidade sobre o e-mail normalizado: "Joao@x.com" e "joao@x.com" são a
-- mesma pessoa, e deixar as duas cadastradas cria uma conta que o dono não
-- consegue acessar porque nunca lembra qual usou.
create unique index uk_conta_email on contas (lower(email));


-- ----------------------------------------------------------------------------
-- tenants — o assinante; a quem pertencem os dados dentro de cada sistema
-- ----------------------------------------------------------------------------
create table tenants (
    id         uuid        primary key default gen_random_uuid(),
    -- É este valor, e não o uuid, que viaja no claim tenant_id do token e que
    -- os sistemas gravam em cada linha. Fica curto e estável de propósito: a
    -- coluna tenant_id do Styllus é varchar(64).
    codigo     text        not null,
    nome       text        not null,
    documento  text,
    criado_em  timestamptz not null default now(),

    constraint uk_tenant_codigo unique (codigo)
);


-- ----------------------------------------------------------------------------
-- conta_tenant — quem pode administrar qual assinante
--
-- Tabela separada, e não uma coluna tenant_id em contas, porque fundir os dois
-- é a modelagem que mais dá trabalho para desfazer: o tenant_id já estaria
-- dentro dos dados de todos os sistemas vendidos. Uma loja com duas unidades,
-- ou um contador que administra três clientes, já quebra a fusão.
-- ----------------------------------------------------------------------------
create table conta_tenant (
    id         uuid        primary key default gen_random_uuid(),
    conta_id   uuid        not null references contas (id)  on delete cascade,
    tenant_id  uuid        not null references tenants (id) on delete cascade,
    papel      text        not null,
    criado_em  timestamptz not null default now(),

    constraint uk_conta_tenant     unique (conta_id, tenant_id),
    constraint ck_conta_tenant_papel check (papel in ('dono', 'operador'))
);

create index idx_conta_tenant_conta on conta_tenant (conta_id);


-- ----------------------------------------------------------------------------
-- produtos — cada sistema à venda na plataforma
-- ----------------------------------------------------------------------------
create table produtos (
    id         uuid        primary key default gen_random_uuid(),
    -- Vira o claim "aud" do token. O sistema rejeita token emitido para outro
    -- produto, então este valor é parte do contrato de acesso.
    codigo     text        not null,
    nome       text        not null,
    descricao  text,
    -- Para onde o botão "Abrir sistema" leva o handoff da etapa 2.
    url_base   text        not null,
    ativo      boolean     not null default true,
    criado_em  timestamptz not null default now(),

    constraint uk_produto_codigo unique (codigo)
);


-- ----------------------------------------------------------------------------
-- planos — preço e ciclo de cada produto
-- ----------------------------------------------------------------------------
create table planos (
    id             uuid        primary key default gen_random_uuid(),
    produto_id     uuid        not null references produtos (id),
    codigo         text        not null,
    nome           text        not null,
    -- Dinheiro em centavos, como inteiro. Ponto flutuante acumula erro de
    -- arredondamento, e o lugar onde isso aparece é a fatura do cliente.
    preco_centavos integer     not null check (preco_centavos >= 0),
    ciclo          text        not null,
    ativo          boolean     not null default true,
    criado_em      timestamptz not null default now(),

    constraint uk_plano_codigo unique (produto_id, codigo),
    constraint ck_plano_ciclo  check (ciclo in ('mensal', 'anual'))
);


-- ----------------------------------------------------------------------------
-- assinaturas — o estado que decide o acesso
-- ----------------------------------------------------------------------------
create table assinaturas (
    id           uuid        primary key default gen_random_uuid(),
    tenant_id    uuid        not null references tenants (id),
    -- Duplicado a partir do plano para sustentar a unicidade abaixo. Mesma
    -- desnormalização deliberada que o Styllus faz com tenant_id em servicos.
    produto_id   uuid        not null references produtos (id),
    plano_id     uuid        not null references planos (id),
    estado       text        not null,

    -- Até quando o acesso está liberado. É o único campo que o handoff da
    -- etapa 2 precisa ler: no trial é o fim do teste; na assinatura ativa é a
    -- data paga mais a carência. Concentrar a resposta num campo evita que a
    -- regra de acesso seja recalculada, diferente, em cada lugar que a
    -- consulta.
    acesso_ate   timestamptz,

    iniciada_em  timestamptz not null default now(),
    cancelada_em timestamptz,
    criada_em    timestamptz not null default now(),
    atualizada_em timestamptz not null default now(),

    -- Um assinante não tem duas assinaturas do mesmo sistema. Sem isto, um
    -- clique duplo no checkout cobra duas vezes pelo mesmo acesso.
    constraint uk_assinatura_tenant_produto unique (tenant_id, produto_id),
    constraint ck_assinatura_estado check (
        estado in ('trial', 'ativa', 'atrasada', 'suspensa', 'cancelada')
    )
);

create index idx_assinatura_tenant on assinaturas (tenant_id);
create index idx_assinatura_estado on assinaturas (estado, acesso_ate);


-- ============================================================================
-- Dados iniciais
-- ============================================================================

-- ATENÇÃO — continuidade com a produção.
--
-- O Styllus está no ar desde 27/08/2026 com APP_TENANT_ID=styllos, e toda a
-- carteira de clientes e serviços da loja já gravada tem tenant_id='styllos'.
-- Este código precisa ser exatamente esse: mudá-lo aqui, ou gerar um uuid novo
-- para a loja, deixa os dados existentes órfãos — visíveis em nenhuma conta.
insert into tenants (codigo, nome) values
    ('styllos', 'Styllus Sapataria');

insert into produtos (codigo, nome, descricao, url_base) values
    ('styllos',
     'Styllus — Gestão de Sapataria',
     'Controle de ordens de serviço em Kanban, cadastro de clientes e aviso automático de retirada por WhatsApp.',
     'https://sapataria.drlog.com.br');

insert into planos (produto_id, codigo, nome, preco_centavos, ciclo)
select id, 'mensal', 'Mensal', 9900, 'mensal' from produtos where codigo = 'styllos';

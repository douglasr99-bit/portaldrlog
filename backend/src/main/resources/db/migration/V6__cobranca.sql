-- ============================================================================
-- V6 — Cobrança recorrente
--
-- Até aqui renovar era um clique na tela, e esquecer significava trancar um
-- cliente que pagou. O gateway passa a avisar por webhook quando o dinheiro
-- entra, e o acesso se estende sozinho.
-- ============================================================================

alter table assinaturas
    -- Identificadores do lado do gateway. Guardados para que uma segunda
    -- ativação não crie um segundo cliente ou uma segunda assinatura lá —
    -- o que resultaria em cobrar duas vezes pelo mesmo acesso.
    add column gateway              text,
    add column gateway_cliente_id   text,
    add column gateway_assinatura_id text;

create unique index uk_assinatura_gateway
    on assinaturas (gateway, gateway_assinatura_id)
    where gateway_assinatura_id is not null;


-- ----------------------------------------------------------------------------
-- cobrancas — espelho local do que existe no gateway
--
-- Espelho, e não fonte: a verdade é do gateway. Esta tabela existe para a
-- tela mostrar o histórico sem depender de uma chamada externa, e para
-- diagnosticar depois o que aconteceu.
-- ----------------------------------------------------------------------------
create table cobrancas (
    id             uuid        primary key default gen_random_uuid(),
    assinatura_id  uuid        not null references assinaturas (id) on delete cascade,

    gateway        text        not null,
    externa_id     text        not null,

    valor_centavos integer     not null check (valor_centavos >= 0),
    vencimento     date,
    status         text        not null,
    pago_em        timestamptz,
    link           text,

    criada_em      timestamptz not null default now(),
    atualizada_em  timestamptz not null default now(),

    -- O mesmo pagamento chega em vários eventos (criado, confirmado,
    -- recebido). Sem esta restrição, cada um viraria uma linha nova e o
    -- histórico da loja mostraria cobranças que não existem.
    constraint uk_cobranca_externa unique (gateway, externa_id)
);

create index idx_cobranca_assinatura on cobrancas (assinatura_id, vencimento desc);


-- ----------------------------------------------------------------------------
-- eventos_gateway — tudo o que o gateway mandou, como mandou
--
-- O Asaas entrega em modelo "at least once": o mesmo evento chega mais de uma
-- vez, por desenho. Um PAYMENT_RECEIVED processado duas vezes, estendendo o
-- acesso a cada passagem, entregaria dois meses por um pagamento.
--
-- A unicidade abaixo é o que impede isso. Não é cuidado de quem escreve o
-- código; é restrição do banco.
-- ----------------------------------------------------------------------------
create table eventos_gateway (
    id            uuid        primary key default gen_random_uuid(),
    gateway       text        not null,
    evento_id     text        not null,
    tipo          text,
    payload       text        not null,

    recebido_em   timestamptz not null default now(),
    processado_em timestamptz,
    erro          text,

    constraint uk_evento_gateway unique (gateway, evento_id)
);

-- A fila do que chegou e ainda não foi tratado.
create index idx_evento_pendente on eventos_gateway (recebido_em)
    where processado_em is null;

-- ============================================================================
-- V8 — Cartão no teste grátis
--
-- O teste passa a exigir cartão. O cartão não é forma de pagamento aqui: é o
-- preço da palavra "grátis". Ele resolve de uma vez o e-mail inventado, a
-- instância de WhatsApp abandonada e o fim de teste que passava em silêncio.
--
-- Quem não quer teste assina direto e paga como puder — Pix, boleto ou
-- cartão. A restrição vale só para quem quer os dias grátis.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- O estado de quem se cadastrou e ainda não pagou nem deixou cartão
--
-- Existe para que a conta possa ser criada ANTES do checkout, sem liberar
-- nada. Sem esse estado intermediário só haveria duas opções ruins: criar a
-- conta já com acesso (e devolver o buraco que o cartão veio fechar) ou só
-- criar depois do pagamento (perdendo quem desiste no meio e volta depois).
--
-- Não libera acesso e não provisiona WhatsApp. É de propósito: é justamente
-- aqui que mora quem abandonou o checkout, e esse é o custo que não queremos.
-- ----------------------------------------------------------------------------
alter table assinaturas drop constraint ck_assinatura_estado;
alter table assinaturas add constraint ck_assinatura_estado check (
    estado in ('aguardando_pagamento', 'trial', 'ativa', 'atrasada', 'suspensa', 'cancelada')
);

alter table assinaturas
    -- O checkout hospedado do Asaas. Guardamos o id para reler o status lá em
    -- vez de acreditar no que chega pela volta do navegador — mesma regra do
    -- webhook: o que vale é o que o Asaas diz, não o que o cliente traz.
    add column gateway_checkout_id text,

    -- O link do checkout, para o painel oferecer "terminar o cadastro" a quem
    -- fechou a aba no meio. Sem ele, quem desistiu por um minuto precisaria
    -- recomeçar tudo — e não recomeça.
    add column checkout_link text,

    -- Cancelamento pedido pelo cliente.
    --
    -- Não vira estado 'cancelada' na hora de propósito: quem pagou até o dia
    -- 30 tem direito ao dia 29. Cancelar interrompe a RENOVAÇÃO; o acesso
    -- termina sozinho quando acesso_ate passa, sem ninguém precisar agir.
    add column renovacao_cancelada boolean not null default false;

-- Quem está pendurado no checkout sem terminar. É a fila que diz se a tela de
-- pagamento está espantando gente.
create index idx_assinatura_aguardando on assinaturas (estado, criada_em)
    where estado = 'aguardando_pagamento';

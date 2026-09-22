-- ============================================================================
-- V2 — Campos da vitrine pública
--
-- A página inicial deixou de ser o login e passou a ser o catálogo: é ela que
-- apresenta os sistemas a quem nunca ouviu falar da Drlog. Estes campos são o
-- que ela precisa saber de cada produto.
-- ============================================================================

alter table produtos
    -- Uma frase concreta sobre o que o sistema faz. Não é slogan: é o texto
    -- que aparece na listagem e que precisa fazer a pessoa se reconhecer.
    add column resumo    text,

    -- Vídeo de demonstração. Nulo enquanto não existir — e a página mostra as
    -- capturas reais no lugar, em vez de um player vazio.
    add column video_url text,

    add column ordem     integer not null default 0;

update produtos set
    resumo = 'Kanban de três colunas para a bancada e aviso automático no '
          || 'WhatsApp quando o serviço fica pronto.',
    ordem  = 1
where codigo = 'styllos';

-- ============================================================================
-- V4 — Chave de assinatura dos tokens de acesso
--
-- O Portal assina; os sistemas vendidos apenas verificam, com a chave
-- pública publicada em /.well-known/jwks.json.
--
-- Assimétrico, e não segredo compartilhado: com HS256 cada sistema carregaria
-- uma chave capaz de FORJAR token de qualquer assinante, e o comprometimento
-- de um sistema periférico viraria o comprometimento da plataforma inteira.
-- Aqui o Styllus só sabe conferir.
-- ============================================================================

create table chaves_jwt (
    -- Vai no cabeçalho "kid" do token. É ele que permite ter duas chaves
    -- publicadas ao mesmo tempo, que é o que torna a rotação possível sem
    -- invalidar os tokens em trânsito.
    kid         text        primary key,

    -- PKCS#8 e X.509, em base64. Quem lê esta tabela consegue emitir token de
    -- qualquer assinante — mesmo raio de alcance de quem lê senha_hash e
    -- pode alterar assinaturas. Não é um segredo a mais para vazar; é o
    -- mesmo banco.
    privada     text        not null,
    publica     text        not null,

    -- Só uma assina por vez. As demais continuam publicadas no JWKS enquanto
    -- houver token emitido por elas circulando.
    ativa       boolean     not null default true,
    criada_em   timestamptz not null default now()
);

-- Garante no banco o que o código pressupõe: uma chave assinando, não duas.
create unique index uk_chave_ativa on chaves_jwt (ativa) where ativa;

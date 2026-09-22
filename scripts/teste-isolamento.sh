#!/usr/bin/env bash
#
# Teste de isolamento entre assinantes, sob concorrência.
#
# Duas lojas entram no Styllus pelo Portal, cadastram dados próprios e são
# consultadas ao mesmo tempo, em paralelo. Cada resposta é conferida: se
# trouxer dado da outra loja, é vazamento.
#
# Por que concorrente: o modo portal guarda o tenant num ThreadLocal, e o
# Tomcat reaproveita threads entre requisições. Um teste sequencial passa
# mesmo com o bug — ele só aparece quando duas lojas disputam as mesmas
# threads.
#
# Pré-requisitos:
#   - Portal rodando (./scripts/dev.sh)
#   - Styllus rodando com APP_AUTH_MODO=portal apontando para o Portal
#   - o produto "styllos" com url_base apontando para o Styllus local
#
# Uso:
#   ./scripts/teste-isolamento.sh <email_admin> <senha_admin>
#
# A segunda loja vem de LOJA2_EMAIL e LOJA2_SENHA, e precisa já existir —
# crie-a pela tela de administração antes de rodar.
#
# Credenciais não têm padrão aqui de propósito: senha versionada num script
# vira senha real em algum ambiente, mais cedo ou mais tarde.
#
set -euo pipefail

PORTAL=${PORTAL:-http://localhost:8082}
STYLLUS=${STYLLUS:-http://localhost:8081}
ADMIN_EMAIL=${1:-}
ADMIN_SENHA=${2:-}
LOJA2_EMAIL=${LOJA2_EMAIL:-}
LOJA2_SENHA=${LOJA2_SENHA:-}

if [[ -z "$ADMIN_EMAIL" || -z "$ADMIN_SENHA" || -z "$LOJA2_EMAIL" || -z "$LOJA2_SENHA" ]]; then
  cat >&2 <<AJUDA
uso: LOJA2_EMAIL=... LOJA2_SENHA=... $0 <email_admin> <senha_admin>

  As duas lojas precisam existir antes: a do administrador e uma segunda,
  criada pela tela de administração.
AJUDA
  exit 2
fi
REQUISICOES=${REQUISICOES:-200}
PARALELAS=${PARALELAS:-30}

D=$(mktemp -d); trap 'rm -rf "$D"' EXIT

csrf() { grep -oP 'name="_csrf" value="\K[^"]+' "$1" | head -1; }

entrar_no_portal() {   # $1 sessão  $2 email  $3 senha
  curl -s -c "$D/p$1" "$PORTAL/login" -o "$D/l$1.html"; sync
  curl -s -o /dev/null -b "$D/p$1" -c "$D/p$1" -X POST \
    -d "email=$2&senha=$3&_csrf=$(csrf "$D/l$1.html")" "$PORTAL/login"; sync
}

abrir_o_styllus() {    # $1 sessão — usa a sessão do Portal já aberta
  curl -s -b "$D/p$1" "$PORTAL/painel" -o "$D/pn$1.html"; sync
  local assinatura token
  assinatura=$(grep -oP 'action="/abrir/\K[0-9a-f-]+' "$D/pn$1.html" | head -1)
  curl -s -b "$D/p$1" -c "$D/p$1" -X POST -d "_csrf=$(csrf "$D/pn$1.html")" \
    "$PORTAL/abrir/$assinatura" -o "$D/ab$1.html"; sync
  token=$(grep -oP 'name="token" value="\K[^"]+' "$D/ab$1.html")
  [[ -n "$token" ]] || { echo "ERRO: o Portal não emitiu token para a sessão $1." >&2; exit 1; }
  curl -s -o /dev/null -c "$D/s$1" -X POST --data-urlencode "token=$token" "$STYLLUS/sso"; sync
}

cadastrar() {          # $1 sessão  $2 nome do cliente
  curl -s -o /dev/null -b "$D/s$1" -c "$D/s$1" -X POST "$STYLLUS/api/servicos" \
    -H 'Content-Type: application/json' \
    -H "X-XSRF-TOKEN: $(awk '/XSRF-TOKEN/{print $7}' "$D/s$1")" \
    -d "{\"nome\":\"$2\",\"telefone\":\"(16) 90000-0000\",\"tipoServico\":\"Conserto\",\"valor\":50.00,\"pagamento\":\"entrega\"}"
  sync
}

cat > "$D/bater.sh" <<'FIM'
#!/bin/bash
# $1 sessão  $2 prefixo proibido  $3 diretório  $4 url do Styllus
resposta=$(curl -s -b "$3/s$1" "$4/api/dados")
if echo "$resposta" | grep -q "$2"; then echo "VAZOU"; else echo "ok"; fi
FIM
chmod +x "$D/bater.sh"

echo "Abrindo as duas lojas…"
entrar_no_portal 1 "$ADMIN_EMAIL" "$ADMIN_SENHA"; abrir_o_styllus 1
entrar_no_portal 2 "$LOJA2_EMAIL" "$LOJA2_SENHA"; abrir_o_styllus 2

for i in 1 2 3; do cadastrar 1 "LOJA-UM-$i"; done
for i in 1 2;   do cadastrar 2 "LOJA-DOIS-$i"; done

echo "Disparando $((REQUISICOES * 2)) requisições, $PARALELAS em paralelo…"
seq 1 "$REQUISICOES" | xargs -P "$PARALELAS" -I{} bash -c "$D/bater.sh 1 LOJA-DOIS $D $STYLLUS" > "$D/r1" &
seq 1 "$REQUISICOES" | xargs -P "$PARALELAS" -I{} bash -c "$D/bater.sh 2 LOJA-UM  $D $STYLLUS" > "$D/r2" &
wait

v1=$(grep -c VAZOU "$D/r1" || true); v2=$(grep -c VAZOU "$D/r2" || true)
echo
echo "  loja 1: $(wc -l < "$D/r1") respostas, $v1 vazamento(s)"
echo "  loja 2: $(wc -l < "$D/r2") respostas, $v2 vazamento(s)"
echo

if [[ "$v1" -eq 0 && "$v2" -eq 0 ]]; then
  echo "  OK — nenhum vazamento."
  echo
  echo "  Atenção: um teste que sempre passa não prova nada. Para conferir que"
  echo "  ele detecta de verdade, sabote o TenantFilter (troque o tenant do"
  echo "  principal por um valor fixo), recompile e rode de novo: os"
  echo "  vazamentos têm de aparecer."
  exit 0
else
  echo "  FALHOU — dados de uma loja apareceram na outra."
  exit 1
fi

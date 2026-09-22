#!/usr/bin/env bash
#
# Sobe o Portal para desenvolvimento: banco, build e aplicação.
#
#   ./scripts/dev.sh          compila e roda
#   ./scripts/dev.sh --limpo  apaga o banco antes (recria do zero pelo Flyway)
#   ./scripts/dev.sh --parar  derruba tudo
#
set -euo pipefail

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BANCO=portal_dev
PORTA_BANCO=55432

parar() {
  # O -f do pkill casaria com a própria linha de comando deste script;
  # por isso o pid é guardado em arquivo.
  [[ -f "$RAIZ/.dev.pid" ]] && kill "$(cat "$RAIZ/.dev.pid")" 2>/dev/null || true
  rm -f "$RAIZ/.dev.pid"
  docker rm -f "$BANCO" >/dev/null 2>&1 || true
  echo "Parado."
}

[[ "${1:-}" == "--parar" ]] && { parar; exit 0; }
[[ "${1:-}" == "--limpo" ]] && docker rm -f "$BANCO" >/dev/null 2>&1 || true

# ---- .env -------------------------------------------------------------------
# Dados reais da empresa (CNPJ, telefone, endereço) e credenciais ficam aqui,
# fora do código e fora do Git. Sem .env, o script usa valores de
# desenvolvimento — fictícios e visivelmente fictícios.
#
# Lido linha a linha, e não com "source": o source executa o arquivo como
# script, então um valor sem aspas com espaço — "Razão Social LTDA" — vira
# tentativa de rodar o comando "Social". Exigir aspas em todo valor seria
# uma armadilha silenciosa num arquivo que a gente edita à mão.
if [[ -f "$RAIZ/.env" ]]; then
  echo "Lendo $RAIZ/.env"
  while IFS='=' read -r chave valor || [[ -n "$chave" ]]; do
    chave="${chave%%$'\r'*}"; valor="${valor%%$'\r'*}"
    chave="${chave#"${chave%%[![:space:]]*}"}"       # tira espaço à esquerda
    chave="${chave%"${chave##*[![:space:]]}"}"       # e à direita
    [[ -z "$chave" || "$chave" == \#* ]] && continue
    # aspas em volta são opcionais; se houver, saem
    [[ "$valor" == \"*\" ]] && valor="${valor:1:-1}"
    [[ "$valor" == \'*\' ]] && valor="${valor:1:-1}"
    export "$chave=$valor"
  done < "$RAIZ/.env"
fi

# ---- JDK 21 -----------------------------------------------------------------
# O sdkman costuma deixar o 17 como padrão, e o Maven usa o JAVA_HOME — não o
# java do PATH. Sem esta busca, o build falha com "release version 21 not
# supported", que não diz onde está o problema.
achar_jdk21() {
  if [[ -n "${JAVA_HOME:-}" ]] && "$JAVA_HOME/bin/java" -version 2>&1 | grep -q '"21'; then
    echo "$JAVA_HOME"; return
  fi
  for d in /usr/lib/jvm/java-21-* "$HOME"/.sdkman/candidates/java/21*; do
    [[ -x "$d/bin/javac" ]] && { echo "$d"; return; }
  done
}

JDK="$(achar_jdk21)"
if [[ -z "$JDK" ]]; then
  echo "ERRO: nenhum JDK 21 encontrado." >&2
  echo "      Instale um (sdk install java 21-tem) ou aponte JAVA_HOME para ele." >&2
  exit 1
fi
echo "JDK 21: $JDK"

# ---- banco ------------------------------------------------------------------
if ! docker ps --format '{{.Names}}' | grep -qx "$BANCO"; then
  echo "Subindo PostgreSQL em localhost:$PORTA_BANCO..."
  docker run -d --name "$BANCO" \
    -e POSTGRES_USER=portal -e POSTGRES_PASSWORD=portal -e POSTGRES_DB=portal \
    -p "$PORTA_BANCO:5432" postgres:16 >/dev/null
  until docker exec "$BANCO" pg_isready -U portal -d portal >/dev/null 2>&1; do sleep 1; done
fi

# ---- build ------------------------------------------------------------------
echo "Compilando..."
JAVA_HOME="$JDK" mvn -B -q -f "$RAIZ/backend/pom.xml" clean package -DskipTests

# ---- aplicação --------------------------------------------------------------
# Cada valor do .env vence o padrão de desenvolvimento abaixo. Em produção
# nada disto existe: as variáveis vêm do Coolify, e lá não há padrão nenhum.
APP_ADMIN_EMAIL="${APP_ADMIN_EMAIL:-contato@drlog.com.br}"
APP_ADMIN_SENHA="${APP_ADMIN_SENHA:-dev12345}"
APP_EMPRESA_RAZAO_SOCIAL="${APP_EMPRESA_RAZAO_SOCIAL:-Drlog Sistemas (desenvolvimento)}"
APP_EMPRESA_CNPJ="${APP_EMPRESA_CNPJ:-00.000.000/0001-00}"
APP_EMPRESA_ENDERECO="${APP_EMPRESA_ENDERECO:-São Paulo, SP}"
APP_EMPRESA_WHATSAPP="${APP_EMPRESA_WHATSAPP:-5511999998888}"
APP_EMPRESA_EMAIL="${APP_EMPRESA_EMAIL:-}"
APP_VITRINE_VAGAS_EM_BREVE="${APP_VITRINE_VAGAS_EM_BREVE:-2}"

echo "Subindo o Portal em http://localhost:8082 ..."
echo "  vitrine: http://localhost:8082/"
echo "  login:   $APP_ADMIN_EMAIL / $APP_ADMIN_SENHA"
[[ -f "$RAIZ/.env" ]] || echo "  (dados da empresa fictícios — crie um .env para usar os reais)"
echo

# O exec preserva o PID deste processo, então gravá-lo aqui faz o --parar
# funcionar mesmo quando o script é deixado rodando em segundo plano.
echo $$ > "$RAIZ/.dev.pid"

exec "$JDK/bin/java" -jar "$RAIZ/backend/target/portal-1.0.0.jar" \
  --DATABASE_URL="jdbc:postgresql://localhost:$PORTA_BANCO/portal" \
  --DATABASE_USER=portal \
  --DATABASE_PASSWORD=portal \
  --APP_ADMIN_EMAIL="$APP_ADMIN_EMAIL" \
  --APP_ADMIN_SENHA="$APP_ADMIN_SENHA" \
  --APP_EMPRESA_RAZAO_SOCIAL="$APP_EMPRESA_RAZAO_SOCIAL" \
  --APP_EMPRESA_CNPJ="$APP_EMPRESA_CNPJ" \
  --APP_EMPRESA_ENDERECO="$APP_EMPRESA_ENDERECO" \
  --APP_EMPRESA_WHATSAPP="$APP_EMPRESA_WHATSAPP" \
  --APP_EMPRESA_EMAIL="$APP_EMPRESA_EMAIL" \
  --APP_VITRINE_VAGAS_EM_BREVE="$APP_VITRINE_VAGAS_EM_BREVE"

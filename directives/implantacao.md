# Implantação do Portal e ativação do SSO

Roteiro para levar o Portal ao ar em `drlog.com.br` e, depois, ligar a entrada
única no Styllus.

Complementa [`arquitetura_portal.md`](arquitetura_portal.md), que explica o
**porquê** de cada peça. Aqui está o **como**, na ordem em que precisa
acontecer.

---

## A ordem não é negociável

```
  1. DNS                         drlog.com.br → 187.127.61.9
  2. Banco                       database "portal" no PostgreSQL que já existe
  3. Portal no ar                sem tocar no Styllus
  4. Conferir o JWKS             é dele que o Styllus depende
  5. Styllus com código novo     comportamento IDÊNTICO ao de hoje
  6. Ligar o modo portal         só agora o formulário sai do ar
```

**Inverter 5 e 6 tranca a loja.** No modo portal o formulário é desativado —
se o Portal não estiver no ar e provisionado, não existe porta de entrada
nenhuma, e a sapataria fica sem sistema até alguém reverter a variável.

Entre o passo 5 e o 6 pode passar um dia. O passo 5 não muda nada para quem
usa: é justamente por isso que `APP_AUTH_MODO` nasce valendo `formulario`.

---

## Por que o Portal fica no apex

`drlog.com.br` é a vitrine — a página que apresenta os sistemas e onde o
assinante entra. `sapataria.drlog.com.br` continua sendo um produto dentro
dela. Colocar a loja num subdomínio e deixar o apex vazio inverteria a
hierarquia.

Verificado antes de decidir: o apex não tem registro A, e o MX aponta para o
Google. **Acrescentar o A não afeta o e-mail** — são registros
independentes.

### `www` é decisão sua, mas escolha uma

O token carrega o emissor (`iss`), e o Styllus recusa token de emissor
diferente. Se o Portal responder em `drlog.com.br` **e** `www.drlog.com.br`,
os dois precisam apontar para o mesmo `APP_PORTAL_URL` — ou o login por um
deles emitirá token que o outro lado recusa.

O caminho simples: `drlog.com.br` é o canônico, e `www` redireciona para ele.

---

## Passo 1 — DNS

No painel do registro.br (os nameservers são `d.sec.dns.br` e `f.sec.dns.br`):

| Nome | Tipo | Valor |
| :--- | :--- | :--- |
| `@` (apex) | A | `187.127.61.9` |
| `www` | A | `187.127.61.9` |

Confirme antes de seguir — o Coolify não emite certificado para um domínio que
não resolve:

```bash
dig +short drlog.com.br A
getent hosts drlog.com.br
```

> Se o Coolify reclamar de DNS logo depois de criar o registro, pode ser só
> propagação. Confira com os comandos acima e use *Verificar novamente o DNS*.

---

## Passo 2 — Banco de dados

O Portal usa um **database próprio no PostgreSQL que já existe**, não um
container novo. Um container de banco por aplicação custa 150–250 MB de RAM, e
a VPS não tem essa folga.

No servidor, dentro do container do PostgreSQL do Styllus:

```bash
docker exec -it <container_postgres> psql -U <usuario_admin> -c "create role portal login password 'ESCOLHA_UMA_SENHA_FORTE';"
docker exec -it <container_postgres> psql -U <usuario_admin> -c "create database portal owner portal;"
```

Isolamento suficiente: o usuário `portal` não enxerga as tabelas do Styllus, e
vice-versa.

> **O que isso não resolve:** backup e restauração continuam sendo do servidor
> inteiro. Restaurar um ponto no tempo afeta todos os databases. Enquanto forem
> poucos assinantes, aceitável — ver a seção 11 do documento de arquitetura.

---

## Passo 3 — O Portal no Coolify

**+ New → Application → Private Repository**, repositório `portaldrlog`,
branch `main`.

| Campo | Valor |
| :--- | :--- |
| Build Pack | `Dockerfile` |
| Dockerfile Location | `/Dockerfile` |
| Base Directory | `/` |
| Port Exposes | **8082** |
| Protocolo | **https** |
| Domínio | `drlog.com.br` *(sem esquema)* |

### Três armadilhas já pagas neste projeto

**O seletor de protocolo.** Com ele em `http`, o Coolify cria só o router da
porta 80 e **nunca pede o certificado** ao Let's Encrypt. O sintoma é HTTP
respondendo 200 e HTTPS devolvendo 503 com certificado autoassinado. Foi o
caso 7.6 do `historico_implantacao.md`.

**O domínio sem esquema.** O campo e o seletor se somam; `https://drlog.com.br`
no campo produz uma URL corrompida.

**A porta volta para 3000** ao trocar o Build Pack. Reconfira 8082 em
*Networking* e no diálogo de domínio depois de qualquer mudança ali.

### Variáveis de ambiente

Modelo completo em [`.env.example`](../.env.example).

**Banco** — sem ele a aplicação não sobe, de propósito:

| Variável | Valor |
| :--- | :--- |
| `DATABASE_URL` | `jdbc:postgresql://<host-interno>:5432/portal` |
| `DATABASE_USER` | `portal` |
| `DATABASE_PASSWORD` | a senha do passo 2 |

⚠️ O prefixo **`jdbc:`** é obrigatório. O Coolify exibe a URL sem ele, e essa
é a falha mais comum desta etapa.

**Primeiro acesso** — usados uma única vez, quando o banco não tem conta
alguma:

| Variável | Valor |
| :--- | :--- |
| `APP_ADMIN_EMAIL` | seu e-mail |
| `APP_ADMIN_SENHA` | **senha forte** — sem ela, é gerada uma aleatória e registrada no log, e não se repete |

**Identidade do Portal** — o `iss` do token, conferido pelo Styllus:

| Variável | Valor |
| :--- | :--- |
| `APP_PORTAL_URL` | `https://drlog.com.br` |

**Evolution** — a chave global fica **só aqui**:

| Variável | Valor |
| :--- | :--- |
| `APP_EVOLUTION_BASE_URL` | `https://api.drlog.com.br` |
| `APP_EVOLUTION_CHAVE` | a `AUTHENTICATION_API_KEY` do container `evolution_api` |
| `APP_EVOLUTION_PREFIXO` | `loja_` |

**Canal servidor-a-servidor** — gere agora e guarde; o Styllus vai precisar do
mesmo valor:

```bash
openssl rand -hex 32
```

| Variável | Valor |
| :--- | :--- |
| `APP_SISTEMAS_SEGREDO` | o valor gerado |

**Rodapé da vitrine** — sem `WHATSAPP` nem `EMAIL`, o botão "Quero assinar"
não aparece:

`APP_EMPRESA_RAZAO_SOCIAL`, `APP_EMPRESA_CNPJ`, `APP_EMPRESA_ENDERECO`,
`APP_EMPRESA_WHATSAPP` *(só dígitos)*, `APP_EMPRESA_EMAIL`.

**Vitrine** — zere as vagas quando o segundo sistema existir:

`APP_VITRINE_VAGAS_EM_BREVE=2`, `APP_VITRINE_ABERTURA=carrossel`.

> Cadastre sem interpolação: um `$` num segredo seria consumido pelo shell.

---

## Passo 4 — Conferir antes de tocar no Styllus

```bash
# a vitrine responde?
curl -sS -o /dev/null -w '%{http_code}\n' https://drlog.com.br/

# o certificado é real?
echo | openssl s_client -connect drlog.com.br:443 -servername drlog.com.br 2>/dev/null \
  | openssl x509 -noout -issuer -dates

# o JWKS publica uma chave? (é disto que o Styllus depende)
curl -sS https://drlog.com.br/.well-known/jwks.json

# o canal está fechado sem segredo?
curl -sS -o /dev/null -w '%{http_code}\n' \
  https://drlog.com.br/api/sistemas/provisionamento/styllos/styllos
```

Esperado: `200`, certificado Let's Encrypt, um objeto em `keys`, e **401** no
canal.

Se `keys` vier vazio, **pare**. O Styllus guarda o JWKS em cache por cinco
minutos; um conjunto vazio guardado agora faz ele recusar tokens legítimos
depois, e o sintoma não aponta para a causa.

### Entre e prepare o assinante

1. `https://drlog.com.br/login` com as credenciais do primeiro acesso
2. **Administração → Contratar** o Styllus para a loja `styllos`
   *(ela já existe: a migração V1 a semeia com esse código exato, que é o
   mesmo `APP_TENANT_ID` gravado em cada linha de `clientes` e `servicos`)*
3. **Provisionar** o WhatsApp — a coluna deve ficar "pronta"

> Se o provisionamento falhar, a tela mostra o motivo vindo da Evolution.
> Resolva aqui: o passo 6 depende dele.

---

## Passo 5 — Styllus com o código novo, sem mudar comportamento

Implante a `main`. **Uma variável nova é obrigatória:**

| Variável | Valor |
| :--- | :--- |
| `APP_LOJA_NOME` | `Styllus Sapataria` |

O nome da loja estava escrito dentro do código e saiu de lá. Sem essa
variável, as mensagens passam a sair **sem o nome da loja** — a aplicação
avisa no log, mas é melhor não descobrir assim.

Tudo o mais continua igual: `APP_AUTH_MODO` vale `formulario`, o login é o de
sempre, a instância e a chave da Evolution são as de hoje.

```bash
# nada mudou para quem usa?
curl -sS https://sapataria.drlog.com.br/api/whatsapp/status
```

Entre pelo login de sempre, conclua um serviço de teste e **confira a
mensagem recebida**: o nome da loja precisa estar lá.

---

## Passo 6 — Ligar a entrada pelo Portal

Só agora. Acrescente ao Styllus:

| Variável | Valor |
| :--- | :--- |
| `APP_AUTH_MODO` | `portal` |
| `APP_PORTAL_EMISSOR` | `https://drlog.com.br` — **idêntico** ao `APP_PORTAL_URL` |
| `APP_PORTAL_JWKS_URI` | `https://drlog.com.br/.well-known/jwks.json` |
| `APP_PORTAL_PRODUTO` | `styllos` |
| `APP_PORTAL_SEGREDO` | o mesmo valor de `APP_SISTEMAS_SEGREDO` |

Reinicie e teste **pelo navegador**, não por curl: entre no Portal, clique em
**Abrir sistema**. Você deve cair dentro do Styllus sem digitar senha.

```bash
# o formulário saiu do ar?
curl -sS -o /dev/null -w '%{http_code}\n' https://sapataria.drlog.com.br/login.html
```

A página de login ainda responde 200 — ela é estática. O que mudou é que o
`POST /login` deixou de autenticar.

### Se der errado

Reverter é uma variável:

```
APP_AUTH_MODO=formulario
```

Reinicie, e o login de sempre volta. Os dados não são afetados — o tenant
continua o mesmo em qualquer um dos modos.

---

## Passo 7 — Medir antes de vender o próximo

```bash
docker stats --no-stream --format 'table {{.Name}}\t{{.MemUsage}}\t{{.CPUPerc}}'
free -h
```

A KVM 1 (1 vCPU / 4 GB) agora sustenta Coolify, Traefik, PostgreSQL, Evolution
com Redis, Styllus **e** Portal. A seção 7 do `plataforma_saas.md` projetava
que isso ficaria no limite.

Com o número real em mãos, a escolha entre KVM 2 e KVM 4 deixa de ser
projeção. E cada assinante novo acrescenta uma instância de WhatsApp, que é a
variável que mais cresce.

---

## O que este roteiro deliberadamente não faz

**Não migra a loja atual para uma instância nova de WhatsApp.** A sapataria
continua com `EVOLUTION_API_INSTANCE` apontando para a instância já pareada.
Trocar significaria parear o número de novo, e não há motivo: o provisionamento
existe para os **próximos** assinantes.

**Não automatiza o provisionamento na contratação.** É um botão na tela. Falhar
no provisionamento não deveria impedir a venda, e o erro precisa ser visível
para quem vende.

**Não liga cobrança.** Continua no WhatsApp, com renovação pela tela de
administração — que avisa sete dias antes do vencimento. A etapa 3 resolve
isso.

---

## Referência rápida de diagnóstico

```bash
# o Portal está no ar?
curl -sS -o /dev/null -w '%{http_code}\n' https://drlog.com.br/

# o JWKS publica chave?
curl -sS https://drlog.com.br/.well-known/jwks.json | head -c 200

# o Styllus enxerga o JWKS de dentro do container?
docker exec <container_styllus> wget -qO- https://drlog.com.br/.well-known/jwks.json | head -c 120

# por que o token foi recusado?
docker logs <container_styllus> --tail 50 2>&1 | grep -i "token recusado"

# por que o provisionamento falhou?
docker logs <container_portal> --tail 50 2>&1 | grep -i provisionar

# 503? compare as portas antes de suspeitar da aplicação
curl -sS -o /dev/null -w "%{http_code}\n" http://drlog.com.br/
curl -sSk -o /dev/null -w "%{http_code}\n" https://drlog.com.br/
```

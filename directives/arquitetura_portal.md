# Arquitetura do Portal Drlog

Contrato e desenho do Portal de assinaturas, escrito **antes** da primeira linha
de código. Fecha as pendências que os documentos do Styllus deixaram em aberto:
o item 3 da seção 8 de `plataforma_saas.md` e o "contrato com o Portal" de
`multi_tenant.md`.

- **Data:** 31 de agosto de 2026
- **Repositório do sistema já em produção:** `StyllosSapataria`
- **Decisões fechadas:** Spring Boot 3 + Java 21, Asaas como gateway

> Os documentos referenciados (`multi_tenant.md`, `plataforma_saas.md`,
> `autenticacao.md`, `migracao_banco_dados.md`, `historico_implantacao.md`,
> `retirada_e_lembretes.md`, `coolify_deployment.md`) vivem no repositório do
> Styllus, não neste. As referências abaixo são por nome.

---

## 1. Por que escrever isto antes do código

O Styllus foi preparado para receber um tenant vindo de fora enquanto o banco
ainda estava vazio — uma janela que já passou. O Portal está na situação
inversa: ele não existe, então **toda decisão ainda é barata**, e as caras são
justamente as que se tomam sem perceber que se está tomando.

Três delas, em particular, são irreversíveis na prática:

- **Como o token é assinado.** Trocar de simétrico para assimétrico depois
  significa coordenar a troca em todos os sistemas vendidos ao mesmo tempo.
- **Onde mora o segredo da Evolution.** Se cada sistema nascer com a chave
  global, recolhê-la depois exige mexer em todos eles.
- **Se o banco do Portal tem migração versionada.** Adotar Flyway com dados de
  cobrança em produção é uma cirurgia; adotar no primeiro commit é uma linha.

---

## 2. O que já está pronto do outro lado

O Styllus está em produção em `sapataria.drlog.com.br`, com PostgreSQL,
autenticação por sessão e `tenant_id` em `clientes` e `servicos`. A leitura do
código confirma o que `multi_tenant.md` promete na camada de dados:

- `ClienteRepository` e `ServicoRepository` estendem `Repository`, não
  `JpaRepository` — não existe método capaz de consultar sem tenant
- `findByIdAndTenantId` protege alteração e exclusão, respondendo 404 quando o
  registro é de outro assinante
- Os índices de `servicos` são compostos, sempre encabeçados por `tenant_id`
- `TenantContext.todos()` permite ao job de lembretes rodar fora de requisição
  sem furar o isolamento

### Três coisas que o código revela e os documentos não registram

**(a) A marca está dentro do texto da mensagem.** `EvolutionApiService` tem
`*Styllus Sapataria*` escrito nas linhas 76 e 103, dentro de
`buildCompletionMessage` e `buildReminderMessage`. Com o segundo assinante, os
clientes dele recebem mensagem assinada com o nome do primeiro. É o pior tipo de
defeito: nada quebra, nenhum log registra, e quem descobre é o cliente da loja.

**(b) `instanceName` é campo de um `@Service` singleton**, resolvido uma vez no
boot via `@Value`. Para virar por-tenant não basta trocar a origem do valor:
enquanto for **estado do objeto**, qualquer "definir antes de usar" é condição
de corrida entre requisições concorrentes — e o efeito de perder essa corrida é
enviar a mensagem da loja A pelo WhatsApp da loja B. O `LembreteRetiradaJob` já
itera tenants em laço, que é exatamente o cenário onde isso acontece.

**(c) A chave da Evolution é global.** O Styllus guarda hoje a
`AUTHENTICATION_API_KEY`, que controla **todas** as instâncias de **todos** os
assinantes. Num sistema de um tenant só isso é irrelevante; com vários, é a
ausência de qualquer contenção para o erro descrito em (b).

Nenhum dos três impede o Portal de existir. Os três precisam estar resolvidos
**antes do segundo assinante**, não depois — ver a sequência na seção 10.

---

## 3. Arquitetura

```
                          Internet
                             │
                             ▼
              ┌──────────────────────────────┐
              │  coolify-proxy (Traefik v3)  │
              └──┬────────────┬───────────┬──┘
                 │            │           │
                 ▼            ▼           ▼
     portal.drlog.com.br  sapataria.  api.drlog.com.br
                 │        drlog.com.br      │
                 │            │             │
        ┌────────▼──────┐  ┌──▼─────────┐   │
        │    PORTAL     │  │  STYLLUS   │   │
        │  Spring Boot  │  │ Spring Boot│   │
        │   Java 21     │  │  Java 17   │   │
        └───┬───────┬───┘  └──┬─────────┘   │
            │       │         │             │
            │       └─── JWKS ┘             │
            │       (verificação do token)  │
            │                 │             │
            │                 └─────────────┤
            ▼                               │
     ┌──────────────┐                 ┌─────▼────────┐
     │ PostgreSQL   │                 │ evolution_api│
     │ db: portal   │                 │  1 instância │
     │ db: styllos  │                 │  por tenant  │
     └──────────────┘                 └──────────────┘
            ▲                                ▲
            │                                │
      ┌─────┴──────┐              provisionamento
      │   Asaas    │              (só o Portal tem
      │  webhook   │               a chave global)
      └────────────┘
```

**Um subdomínio por sistema, não por tenant.** `sapataria.drlog.com.br` atende
todas as sapatarias assinantes; o tenant vem do token, não do endereço.
Subdomínio por cliente exigiria certificado wildcard e criação de registro DNS a
cada venda — trabalho manual dentro do fluxo que deveria ser automático.

**O Portal nunca escreve no banco de um sistema vendido.** É a regra do
`multi_tenant.md`, e ela se mantém invertendo o sentido: quando o Styllus
precisa de um dado do Portal, é o Styllus quem busca.

---

## 4. O contrato de acesso

### 4.1 Assinatura assimétrica, não segredo compartilhado

O token é assinado com **RS256** (par de chaves), e o Portal publica a chave
pública em `https://portal.drlog.com.br/.well-known/jwks.json`.

**Por quê:** com HS256 o segredo é o mesmo dos dois lados, então cada sistema
vendido carrega uma chave capaz de **forjar** um token de qualquer tenant de
qualquer produto. O comprometimento de um sistema periférico viraria o
comprometimento da plataforma inteira. Com par de chaves, o Styllus só sabe
verificar — não tem poder algum sobre identidade.

O custo é um endpoint a mais e rotação de chave por `kid`. É pequeno, e só fica
pequeno agora: HS256 hoje significa coordenar a troca em todos os sistemas ao
mesmo tempo, mais tarde.

### 4.2 Handoff que vira sessão, não Bearer em toda chamada

```
Navegador                    Portal                      Styllus
    │                          │                            │
    ├─ clica "Abrir sistema" ─▶│                            │
    │                          │ verifica assinatura ativa  │
    │                          │ emite JWT (exp 60s, jti)   │
    │◀─ form auto-submit ──────┤                            │
    │                                                       │
    ├─ POST /sso (token) ──────────────────────────────────▶│
    │                                                       │ valida via JWKS
    │                                                       │ consome o jti
    │                                                       │ cria a sessão
    │◀─ 302 /index.html + JSESSIONID + XSRF-TOKEN ──────────┤
    │                                                       │
    ├─ POST /api/servicos (X-XSRF-TOKEN) ──────────────────▶│ 201
```

**Por quê:** o Styllus já tem sessão por cookie funcionando, com CSRF, tratamento
de 401 e regeneração de token — tudo depurado em `autenticacao.md`, incluindo
quatro armadilhas que custaram tempo para achar. Trocar isso por Bearer
significaria reescrever `http.js`, `storage.js` e o tratamento de expiração das
seis telas, para chegar exatamente no mesmo lugar.

Com o handoff, o `SecurityConfig` **ganha** um filtro em `/sso` e **perde** o
formulário de login. Nada mais no frontend muda.

**Por que POST auto-submit e não `?token=` na URL:** query string entra em log de
acesso do Traefik, em histórico de navegador e no cabeçalho `Referer` de
qualquer requisição seguinte. O corpo de um POST não vai para nenhum dos três.

**Por que `jti` de uso único:** mesmo com 60 segundos de validade, um token
capturado é reutilizável dentro da janela. Consumir o `jti` numa tabela do
Styllus (com expurgo do que passou de `exp`) fecha isso.

### 4.3 O que o token carrega — e o que ele nunca carrega

```json
{
  "iss": "https://portal.drlog.com.br",
  "aud": "styllos",
  "sub": "usr_a3f9c2e1",
  "tenant_id": "ten_7f3ab204",
  "produto": "styllos",
  "plano": "mensal",
  "papel": "dono",
  "loja_nome": "Styllus Sapataria",
  "jti": "9c1e...",
  "iat": 1756...,
  "exp": 1756...
}
```

`aud` é verificado pelo Styllus: um token emitido para outro produto é rejeitado
mesmo sendo válido e assinado. Sem isso, o token de qualquer sistema abriria
qualquer outro.

**O token nunca transporta segredo.** Ele passa pelo navegador do usuário. A
chave da instância de WhatsApp do tenant vem por canal de servidor para servidor
— seção 7.

### 4.4 Revogação: a sessão é o relógio

Um token de 60 segundos não resolve nada sozinho se a sessão que ele cria durar
para sempre. Uma loja com assinatura cancelada continuaria trabalhando.

**Decisão: sessão com validade absoluta de 12 horas no Styllus**, não apenas
inatividade. A loja reentra pelo Portal no dia seguinte, e é o Portal que decide
se ainda pode.

**Por quê:** é a opção que não cria acoplamento nenhum. A alternativa — o Styllus
consultar entitlement no Portal periodicamente — corta o acesso em minutos em
vez de horas, mas introduz uma dependência de disponibilidade: o Portal fora do
ar passaria a derrubar os sistemas vendidos, transformando uma falha isolada em
falha total. Doze horas de tolerância para um cancelamento é um custo aceitável;
indisponibilidade em cascata não é.

**Quando revisar:** se aparecer fraude real (alguém explorando a janela), ou
quando houver assinantes suficientes para que 12 horas de uso indevido tenha
valor material.

---

## 5. Modelo de dados do Portal

### 5.1 Flyway desde o primeiro commit

O Styllus usa `ddl-auto: update`, e `migracao_banco_dados.md` registra isso como
adequado ao porte — com a ressalva de migrar "antes que uma alteração destrutiva
passe despercebida".

No Portal essa ressalva não é futura. O banco guarda **estado de assinatura e
histórico de cobrança**: os dados que decidem quem tem acesso e quem pagou o
quê. Uma coluna que o Hibernate resolve dropar sozinho não é um contratempo, é
uma disputa com um cliente sem como reconstruir a verdade.

### 5.2 Tabelas

| Tabela | Guarda | Observação |
| :--- | :--- | :--- |
| `contas` | pessoa que faz login: e-mail, hash de senha | e-mail único, `citext` ou normalizado |
| `tenants` | o assinante: nome da loja, documento, status | é o `tenant_id` que viaja no token |
| `conta_tenant` | vínculo pessoa ↔ assinante, com papel | uma pessoa pode ter mais de uma loja |
| `produtos` | Styllus e os próximos | `codigo` é o `aud` do token |
| `planos` | preço, ciclo, limites | vários por produto |
| `assinaturas` | tenant + plano + estado + datas | o coração da decisão de acesso |
| `cobrancas` | espelho local da cobrança no Asaas | id do Asaas, valor, vencimento, status |
| `eventos_gateway` | todo webhook recebido, cru | `unique(gateway, evento_id)` — seção 6.2 |
| `provisionamentos` | instância de WhatsApp por tenant | nome da instância e token — seção 7 |
| `tickets_sso` | `jti` já consumido | expurgo do que passou de `exp` |

**`tenants` separado de `contas` desde o começo.** Fundir os dois (uma conta = um
assinante) é mais simples hoje e é a modelagem que quase toda plataforma pequena
escolhe — e é também a que mais dá trabalho para desfazer, porque o `tenant_id`
já foi para dentro dos dados de todos os sistemas vendidos. Uma sapataria com
duas unidades, ou um contador que administra três clientes, já quebra a fusão.
A tabela de vínculo custa uma junção; separar depois custa migração coordenada.

---

## 6. Cobrança com Asaas

### 6.1 Uma contradição na documentação, e como contorná-la

A FAQ de assinaturas do Asaas afirma que **não existem webhooks de assinatura,
apenas de cobrança**, e que a gestão deve ser feita pelos eventos de `payment`.
A página "Eventos para assinaturas" da mesma documentação lista sete eventos
`SUBSCRIPTION_*`. As duas páginas estão publicadas ao mesmo tempo.

**Decisão: a máquina de estados é dirigida pelos eventos `PAYMENT_*`.** Os
`SUBSCRIPTION_*`, se chegarem, são tratados como informação complementar — nunca
como a única fonte para liberar ou cortar acesso.

**Por quê:** o evento de cobrança é o que corresponde a dinheiro tendo (ou não)
entrado. Se apenas um dos dois conjuntos for confiável, é esse. Apostar no
conjunto que a própria FAQ diz não existir seria construir sobre a parte
duvidosa da documentação.

Toda cobrança gerada por uma assinatura carrega o atributo `subscription` no
payload — é por ele que o evento é ligado à assinatura local.

### 6.2 Idempotência é tabela, não cuidado

O Asaas entrega em modelo **"at least once"**: o mesmo evento chega mais de uma
vez, por desenho. Um `PAYMENT_RECEIVED` processado duas vezes que estenda o
vencimento em um mês a cada passagem entrega dois meses de acesso por um
pagamento.

```
INSERT INTO eventos_gateway (gateway, evento_id, tipo, payload, recebido_em)
  -- unique(gateway, evento_id): a segunda entrega colide e não faz nada
```

**Grave o evento, responda 2xx, processe depois.** O Asaas interrompe a fila após
**15 falhas consecutivas** e descarta eventos não entregues após **14 dias**. Um
processamento lento ou com exceção dentro do handler não atrasa um evento — para
todos.

### 6.3 Nunca confiar no corpo do webhook

O endpoint é público. Ao receber um evento:

1. Conferir o cabeçalho `asaas-access-token` contra o token configurado no
   webhook — **que não pode ser a chave da API**, conforme a própria
   documentação do Asaas recomenda
2. **Reconsultar a cobrança na API do Asaas pelo id** antes de mudar qualquer
   estado

O passo 2 é o que importa. Sem ele, quem descobrir a URL e o token consegue
declarar pagamentos que não aconteceram. Com ele, o webhook vira apenas um
aviso de "algo mudou, vá conferir" — e o pior que um atacante consegue é fazer o
Portal reler a verdade.

### 6.4 Estados da assinatura

```
   trial ──────┐
               ├──▶ ativa ──▶ atrasada ──▶ suspensa ──▶ cancelada
   (paga) ─────┘        ▲          │            │
                        └──────────┴────────────┘
                            (pagamento confirmado)
```

| Estado | Acesso | Entra quando |
| :--- | :--- | :--- |
| `trial` | ✅ liberado | cadastro, sem cobrança ainda |
| `ativa` | ✅ liberado | `PAYMENT_CONFIRMED` / `PAYMENT_RECEIVED` |
| `atrasada` | ✅ **liberado** | `PAYMENT_OVERDUE` |
| `suspensa` | ❌ bloqueado | N dias em `atrasada` (padrão: 5) |
| `cancelada` | ❌ bloqueado | pedido do cliente ou inadimplência longa |

**`atrasada` continua liberando o acesso, e isso é deliberado.** Uma loja de
bairro pagando por boleto atrasa dois dias com frequência. Cortar o sistema no
primeiro dia de atraso gera churn e chamado de suporte por um problema que se
resolveria sozinho — e o sistema cortado é justamente o que a loja usa para
faturar e conseguir pagar. A carência de 5 dias custa pouco e evita quase todo
esse atrito.

O que a loja **vê** durante `atrasada` é um aviso no Portal, não silêncio.

---

## 7. Provisionamento da instância de WhatsApp

### 7.1 O Portal fica com a chave global; os sistemas, nunca

Verificado na Evolution API v2: `POST /instance/create` devolve um campo `hash`,
que é o **token daquela instância** — e é possível informar um `token` próprio na
criação, desde que único. A Evolution opera com dois níveis: chave global para
administrar, token por instância para operar.

```
Assinatura ativada
  → Portal cria a instância na Evolution  (chave global)
  → Portal guarda instancia + token em `provisionamentos`
  → Styllus pede ao Portal os dados do seu tenant  (canal servidor↔servidor)
  → Styllus envia mensagens com o token da instância  (nunca a chave global)
```

**Por quê:** é o que contém o achado (b) da seção 2. Com token por instância, um
erro de roteamento de tenant no Styllus resulta em **401 da Evolution**, não em
mensagem enviada pelo WhatsApp da loja errada. A diferença entre um bug que
falha e um bug que entrega o dano é essa.

O canal servidor↔servidor tem credencial própria por sistema — não é o JWT do
usuário, que trafega pelo navegador.

### 7.2 O Styllus ganha uma tabela

`multi_tenant.md` afirma que "tudo o mais — entidades, repositórios, regras de
negócio, telas — continua como está". Isso vale para os dados do negócio, mas o
Styllus vai precisar de **uma** tabela nova: `tenant_config`, com `tenant_id`,
`loja_nome`, `wa_instance`, `wa_token` e `atualizado_em`.

Ela existe por dois motivos que a seção 2 já apontou: o nome da loja precisa
sair de dentro do texto da mensagem (achado (a)), e o token da instância não
pode viajar no JWT (seção 4.3). Preenchida no primeiro SSO e revalidada por TTL,
ela também mantém o Styllus funcionando quando o Portal está fora do ar — o que
é o mesmo raciocínio da seção 4.4.

`TenantContext.todos()`, usado pelo job de lembretes, passa a ler dela. Se o
Portal estiver indisponível, o job trabalha com o último retrato conhecido em
vez de não trabalhar.

---

## 8. O que muda no Styllus, em resumo

| # | Onde | Mudança |
| :--- | :--- | :--- |
| 1 | `TenantContext` | `atual()` lê da requisição (ThreadLocal com limpeza em `finally`); `todos()` lê de `tenant_config` |
| 2 | `SecurityConfig` | ganha filtro `/sso` com validação JWKS; formulário de login sai (ou fica atrás de flag, para desenvolvimento local) |
| 3 | `EvolutionApiService` | `instanceName` e `apiKey` deixam de ser campos e viram parâmetro; nome da loja sai do texto e vem de `tenant_config` |
| 4 | novo | tabela `tenant_config` + cliente do canal servidor↔servidor com o Portal |

Os pontos 1 e 2 eram os previstos em `multi_tenant.md`. O 3 é maior do que o
documento estimava, pelos achados (a) e (b). O 4 não estava previsto.

**Manter o login por formulário atrás de uma flag** vale a pena: sem ele, o
desenvolvimento local do Styllus passa a exigir um Portal rodando junto, e uma
indisponibilidade do Portal deixa de ter qualquer saída manual.

---

## 9. Infraestrutura

### 9.1 Mesmo PostgreSQL, databases separados

`portal` e `styllos` como databases distintos, com usuários distintos, no
**mesmo container**.

**Por quê:** um container de banco por aplicação custa 150–250 MB de RAM cada, e
a seção 7 de `plataforma_saas.md` já calcula que a base fixa chega perto do
limite da KVM 1. Database e usuário separados dão o isolamento que importa
(nenhuma aplicação enxerga a tabela da outra) sem o custo que não importa.

**O que isso não resolve:** backup e restauração continuam sendo do servidor
inteiro. Restaurar um ponto no tempo para um assinante afetaria todos — ver
seção 11.

### 9.2 Limite de memória por JVM

`plataforma_saas.md` registra isto como pendência e observa que não foi feito nem
no Styllus. Com Portal e Styllus dividindo a máquina, deixar de fazer significa
que a primeira aplicação a crescer sufoca a outra.

```
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "app.jar"]
```

Percentual em vez de `-Xmx` fixo: o valor acompanha o limite do container e
sobrevive a um upgrade de VPS sem precisar ser reajustado.

### 9.3 Java 21 no Portal, 17 no Styllus

Não é problema — são containers separados. O Portal nasce em 21 por ser LTS mais
recente e ter comportamento de memória melhor em container. O Styllus sobe para
21 quando houver outro motivo para mexer no Dockerfile dele; não vale um deploy
só para isso.

---

## 10. Sequência

| # | Etapa | Entrega |
| :--- | :--- | :--- |
| 0 | `docker stats` e `free -h` na VPS; `MaxRAMPercentage` no Styllus | decisão de upgrade com número medido |
| 1 | ~~Esqueleto do Portal: Flyway, `contas`, `tenants`, login~~ | ✅ **feito** — ver seção 12 |
| 2 | JWKS + emissão de token + `/sso` no Styllos | **primeira venda possível** |
| 3 | Catálogo, planos, Asaas, webhook, máquina de estados | venda sem intervenção |
| 4 | Instância por tenant + `tenant_config` + achados (a) (b) (c) | **segundo assinante possível** |
| 5 | Trial e cadastro self-service | escala |

**A etapa 2 já permite faturar.** Com SSO funcionando e assinatura ativada à mão,
a cobrança pode ser um link de pagamento avulso do Asaas enviado por WhatsApp. O
cliente paga, você marca ativa, ele entra. Isso antecipa a primeira receita em
semanas em relação a esperar a etapa 3 — e o que se aprende com um assinante real
vale mais que a automação construída no escuro.

**A etapa 4 é a barreira do segundo assinante**, não do primeiro. Enquanto houver
uma loja só, os achados (a), (b) e (c) são inofensivos. Com duas, cada um deles
manda mensagem errada para cliente de verdade.

---

## 11. O que fica em aberto

**Backup por tenant.** O banco compartilhado tem backup agendado no Coolify, mas
restaurar um ponto no tempo afeta todos os assinantes. Enquanto forem poucos, o
risco é aceitável; a partir de um assinante que dependa do sistema para faturar,
vale um export lógico por tenant antes de qualquer restauração ser necessária.

**Rotação da chave RS256.** O desenho prevê `kid` no cabeçalho do token e mais de
uma chave no JWKS, o que torna a rotação possível sem parar nada. O procedimento
em si não está escrito — e procedimento não escrito é procedimento que não
existe no dia em que precisa ser executado às pressas.

**Limite de tentativas de login.** `autenticacao.md` deixou isso em aberto no
Styllus. Migrando o login para o Portal, a pendência migra junto — e fica mais
séria, porque o endereço do Portal é público por natureza, enquanto o do Styllus
circulava pouco.

**Volume de mensagens automáticas.** `retirada_e_lembretes.md` registra que os
lembretes não têm teto e que envio automático em excesso é caminho conhecido
para bloqueio de número. Com uma instância por assinante o risco deixa de ser
concentrado — mas passa a ser um problema de suporte multiplicado por cliente, e
o bloqueio acontece no número **da loja**, não no seu.

**Segredos por assinante.** `multi_tenant.md` alerta que o padrão de "segredo
nascido de exemplo de documentação" tende a se repetir com um token por cliente.
O desenho da seção 7 ajuda — os tokens são gerados pela Evolution, não escritos
por alguém — mas a credencial do canal servidor↔servidor entre Portal e cada
sistema é escrita à mão, e é exatamente do tipo que acaba num `.env.example`.


---

## 12. Etapa 1 — o que foi construído

Spring Boot 3.5.16 sobre Java 21, em `backend/`, com o mesmo formato de imagem
do Styllus: Dockerfile multi-stage na raiz, jar compilado dentro da imagem,
porta interna alcançada pelo Traefik.

| Peça | Estado |
| :--- | :--- |
| Migração `V1__esquema_inicial.sql` | contas, tenants, conta_tenant, produtos, planos, assinaturas |
| Login por sessão, com CSRF | formulário renderizado pelo Thymeleaf |
| Painel do assinante | lojas, assinaturas e catálogo |
| Primeiro acesso | conta criada no boot quando o banco não tem nenhuma |
| Imagem Docker | build limpo, `-XX:MaxRAMPercentage=70` |

### Por que Spring Boot 3.5 e não 4.x

O 4.1.1 já está publicado. Ficou de fora porque traz o Spring Security 7, e o
que motivou escolher a mesma stack do Styllus foi reaproveitar o que já está
depurado — as quatro armadilhas de CSRF, entry point e rotas públicas
registradas em `autenticacao.md`. Adotar o Boot 4 no Portal invalidaria
justamente esse proveito. Quando migrar, migrar os dois juntos.

### O que mudou em relação ao planejado nas seções anteriores

**`ddl-auto: validate`, não apenas Flyway.** A seção 5.1 decidiu usar migração
versionada; na implementação o Hibernate ficou em `validate`. A diferença
importa: com `update`, Flyway e Hibernate alterariam o esquema, e seriam duas
fontes de verdade para a mesma coisa. Agora o Flyway cria e o Hibernate apenas
confere — uma entidade que divirja da migração derruba o boot, em vez de
produzir uma coluna que ninguém decidiu criar.

**`acesso_ate` virou o campo único da decisão de acesso.** A seção 4.4 falava em
estado e validade; `Assinatura.permiteAcesso()` exige as duas coisas. O estado
pode estar velho — o webhook falhou, a rotina de vencimento não rodou —, e foi
verificado que uma assinatura `ativa` com data vencida é corretamente bloqueada.
Sem a data, esse caso passaria.

**O cookie de sessão precisou de ajuste.** O Tomcat reescrevia a URL como
`/login;jsessionid=...` enquanto não recebia o cookie de volta, colocando o
identificador de sessão no log do proxy, no histórico do navegador e no
`Referer` de todo link seguinte. Resolvido com `tracking-modes: cookie`. O
atributo `Secure` ficou deliberadamente não fixado: o Tomcat o aplica sozinho
quando a requisição é HTTPS, e forçá-lo quebraria o desenvolvimento local em
`http://localhost` — que é o caminho conhecido para alguém desligar a linha.

**A senha do primeiro acesso não se repete.** No Styllus a senha aleatória é
regerada a cada reinício, porque vive em memória. Aqui ela é gravada no banco:
aparece uma vez no log e não volta. O texto do aviso diz isso.

### Continuidade com a produção

A V1 semeia o tenant com `codigo = 'styllos'`, que é exatamente o valor de
`APP_TENANT_ID` na instalação em produção e o que está gravado em cada linha de
`clientes` e `servicos` da loja. Gerar um código novo aqui deixaria a carteira
inteira órfã — visível em nenhuma conta. Está comentado na própria migração,
porque é o tipo de valor que alguém "arruma" sem saber o que sustenta.

### Verificação feita

| Cenário | Resultado |
| :--- | :--- |
| `GET /painel` e `GET /` sem sessão | 302 → `/login` |
| `/login` e `/css/**` sem sessão | 200 |
| `POST /login` sem token CSRF | rejeitado, não autentica |
| Senha errada | 302 → `/login?erro` |
| E-mail inexistente | 302 → `/login?erro`, resposta idêntica à senha errada |
| Login correto | 302 → `/painel` |
| `CONTATO@Drlog.com.br` entra na conta de `contato@drlog.com.br` | ✅ |
| `POST /logout` e acesso seguinte | 302 → `/login?saiu`, depois barrado |
| Identificador de sessão na URL | ausente |
| Flyway numa base vazia | V1 aplicada; `ddl-auto: validate` aceitou as entidades |
| Segundo boot | migração não reexecutada |
| Assinatura `ativa`, válida | libera, selo verde, botão presente |
| Assinatura `ativa`, **vencida** | **bloqueia** |
| Assinatura `suspensa`, data futura | bloqueia |
| `docker build` a partir da raiz | imagem gerada, aplicação sobe e responde |
| Avisos na inicialização | nenhum |

### O que ficou pendente desta etapa

**Não há cadastro de conta pela tela.** Contas novas, tenants novos e
assinaturas são criados no banco, à mão. É suficiente para as etapas 2 e 3 — a
primeira venda é ativada manualmente de qualquer forma — e vira tela de verdade
na etapa 5.

**O botão "Abrir sistema" está desabilitado.** Ele só funciona quando o handoff
da etapa 2 existir; habilitá-lo antes levaria o assinante a um sistema que
ainda não sabe receber o token.

**O consumo de memória ainda não foi medido sob limite.** A imagem usou ~445 MB
numa máquina sem limite de container, o que diz pouco: `MaxRAMPercentage` é
relativo ao limite, e não havia um. O número que interessa sai da VPS, com o
limite que o Coolify aplicar.


---

## 13. A vitrine — a página pública

A etapa 1 entregou a área do assinante. Faltava o principal: a página que
**quem ainda não é cliente** vê. As duas têm objetivos opostos — uma serve
quem já paga, a outra precisa convencer quem nunca ouviu falar da empresa — e
por isso vivem em controllers separados (`SiteController` e
`PainelController`). Misturá-los é como uma rota de venda acaba exigindo
login, ou uma rota do painel acaba pública.

### Posicionamento

> Sistemas para negócios de bairro que trabalham com ordem de serviço.

Sapataria, lavanderia, conserto de bicicleta, assistência técnica: todo
negócio em que alguém entrega uma coisa, o dono trabalha nela e devolve.

É um recorte estreito de propósito. O visitante se reconhece na primeira
frase ou vai embora — as duas coisas são melhores que ler três parágrafos sem
descobrir se aquilo serve para ele. O custo é que um produto futuro fora
desse recorte exigirá rever o texto de abertura.

### O problema de a página parecer feita por IA

Não é o visual ser feio — é ser previsível. A estrutura se repete sempre:
herói centralizado com frase de efeito e dois botões, grade de três colunas
com ícones, "por que nos escolher", depoimentos, chamada final.

As decisões abaixo existem para recusar cada peça disso.

| Padrão recusado | O que foi feito no lugar | Por quê |
| :--- | :--- | :--- |
| Herói centralizado com slogan | Grade assimétrica: texto num terço à esquerda, captura do sistema sangrando pela margem direita | Quem chega decide em segundos se aquilo é sério; uma tela real responde mais rápido que qualquer texto |
| Página escura com gradiente | Papel quente e claro | As capturas do Styllus são escuras — numa página escura elas se dissolvem no fundo; aqui viram a única massa escura da tela |
| Grotesca genérica em tudo | Serifa de display nos títulos | Usar a mesma fonte que todo SaaS usa é parte do que faz uma página parecer igual à outra |
| Azul ou roxo de acento | Couro queimado | Raro em software e pertencente ao universo de quem trabalha com as mãos, sem ser literal |
| Grade de três cards | Lista de fichas separadas por fio, numeradas | Com **um** sistema no ar, a grade de três obrigaria a inventar dois |
| Ícone, emoji, sombra difusa, canto arredondado | Fio de 1px, espaço, canto de 3px | A separação é feita como em página impressa |
| ~~Movimento no topo~~ | **Revertido** — a abertura passou a ser um carrossel | Decisão do dono do produto; ver seção 14 |
| Prova social fabricada | Nenhuma | Ver abaixo |

### O teste que o texto precisa passar

Se trocar "Styllus" por qualquer outro nome e a frase continuar fazendo
sentido, ela não diz nada. Foi por isso que a abertura virou *"O cliente
deixa. Você conserta. O sistema avisa."* e não *"Transforme a gestão do seu
negócio"*.

O mesmo vale para os passos: cada um traz a tela em que ele acontece, e o
passo 03 mostra **a mensagem que o sistema envia de verdade** — o texto saiu
de `EvolutionApiService.buildCompletionMessage`, com o mesmo negrito que o
WhatsApp aplica. Não é ilustração; é o conteúdo do código.

### O que deliberadamente não entrou

Depoimento, logo de cliente, "+500 lojas atendidas", selo de segurança.

Prova social fabricada é o cheiro mais forte de página gerada — e, antes
disso, é mentira. A página fica sem a seção até existir material verdadeiro.
A loja em produção ainda não autorizou ser citada pelo nome; quando
autorizar, uma linha verificável vale mais que qualquer depoimento.

O único dado de origem externa hoje é o carimbo **"em produção desde agosto
de 2026"**, que é sobre o software e é verdade.

### As capturas são reais

Não são mockups. O Styllus foi executado localmente contra um PostgreSQL,
populado com sete serviços distribuídos pelo Kanban, e as telas foram
capturadas em Chrome headless a 2x — inclusive a do celular, que é como o
dono da loja de fato olha o sistema. A legenda diz "captura do sistema real,
com dados de exemplo", porque é exatamente isso.

O roteiro está em [`scripts/capturar-telas.js`](../scripts/capturar-telas.js),
versionado porque, quando a interface do Styllus mudar, as capturas ficam
velhas e sem ele ninguém lembra como refazê-las.

### Dados da empresa sem valor padrão

`APP_EMPRESA_RAZAO_SOCIAL`, `_CNPJ`, `_ENDERECO`, `_WHATSAPP` e `_EMAIL`.
Nenhum tem padrão: um CNPJ de exemplo que vaze para produção é informação
falsa sobre quem está cobrando — e este projeto já viu um valor de exemplo de
documentação virar a senha real da Evolution.

A página omite o que não estiver preenchido, e a aplicação avisa no log o que
falta. Sem `APP_EMPRESA_WHATSAPP` nem `_EMAIL`, **o botão "Quero assinar" não
aparece**: não há canal para vender, e um botão que não leva a lugar nenhum é
pior que botão nenhum.

### Defeito encontrado no caminho

Para o Thymeleaf, a string vazia é um valor **verdadeiro** — só `null` é
falso. Com as variáveis de ambiente não preenchidas, o `th:if` passava e o
rodapé exibia **"CNPJ" sem número nenhum**. Num rodapé que existe justamente
para provar que há uma empresa de verdade por trás, o rótulo pelado faz o
oposto do que deveria.

Corrigido nos setters de `Empresa`, que convertem branco em nulo. Vale para
qualquer propriedade de configuração que chegue a um template.

### Verificação feita

| Cenário | Resultado |
| :--- | :--- |
| `/` e `/sistemas/{codigo}` sem sessão | 200 |
| `/sistemas/nao-existe` | 404 |
| `/painel` sem sessão | 302 → login |
| Login, painel autenticado, logout | 302 → `/painel`, 200, volta à vitrine |
| Rodapé **sem** dados da empresa | blocos vazios somem; nenhum rótulo pelado |
| Rodapé **com** dados | razão social, CNPJ, endereço e link `wa.me` corretos |
| "Quero assinar" sem canal configurado | botão ausente |
| "Quero assinar" com WhatsApp | link `wa.me` com mensagem pré-escrita |
| Celular (390px) | captura do celular no lugar da de desktop |
| Avisos na inicialização | nenhum, exceto o de dados da empresa faltando |

### O que fica em aberto nesta parte

**O vídeo de demonstração não existe.** A coluna `video_url` está pronta e o
template já troca a captura pelo player quando ela é preenchida. Enquanto não
houver vídeo, a página mostra a tela grande — nunca um player vazio.

**O checkout não existe** (etapa 3). Até lá, "Quero assinar" abre uma conversa
no WhatsApp, que é como as primeiras assinaturas seriam fechadas de qualquer
forma.

**Conteúdo de vitrine por produto está no template.** Os passos "como
funciona" e suas capturas são específicos do Styllus e estão escritos em
`produto.html`. Com um produto isso é honesto e flexível. **Ao chegar o
terceiro, vira tabela** — antes disso, o template começa a ganhar condicionais
por código de produto, que é o sintoma de que se esperou demais.

**A página carrega fontes do Google Fonts.** É uma dependência externa numa
página cujo trabalho é transmitir confiança. Há fallback declarado, então uma
falha degrada a tipografia sem quebrar o layout; ainda assim, hospedar as
fontes junto com a aplicação elimina a dependência e o rastreamento de
terceiros.


---

## 14. A abertura virou carrossel

**Decisão do dono do produto, contra a recomendação registrada aqui.** Fica
anotado dos dois lados: o que foi argumentado e o que foi feito para que a
escolha funcionasse bem.

### O que foi argumentado contra

Três coisas, todas verificadas na prática ao construir as variantes:

1. **Movimento no topo disputa com o texto.** A direção "prova em primeiro
   lugar" funciona porque *uma* tela domina. Com rotação, o visitante olha o
   que se mexe em vez de ler a frase de abertura.
2. **Conteúdo some antes de ser lido.** Quem começou a ler a legenda e viu o
   slide virar fica sem ela.
3. **Com um sistema só, a rotação é de telas, não de sistemas** — é tour de
   produto, não vitrine. O objetivo original ("apresentar todos os sistemas")
   só é atendido de fato a partir do segundo produto.

Nada disso foi resolvido pela implementação; são propriedades da escolha. O
que foi resolvido é tudo o que costuma faltar num carrossel.

### As variantes comparadas, e o que cada uma revelou

Foram construídas quatro aberturas no app real, com as capturas verdadeiras, e
comparadas lado a lado antes da decisão.

| Variante | O que se aprendeu construindo |
| :--- | :--- |
| **Fixa** | Mantida como alternativa de configuração |
| **Seletor** | Funcionava bem; o visitante no controle, sem movimento |
| **Carrossel** | **Escolhida** |
| **Mosaico** | Descartada: as telas menores ficam **ilegíveis** e viram decoração feita de captura — ocupa o espaço da prova sem provar nada |

O mosaico também precisou ser refeito no meio do caminho: a primeira versão
empilhava as peças com deslocamento e escala, e as de trás sumiam atrás da da
frente. Lia como erro de renderização. Virou grade — e aí ficou claro que o
problema não era o empilhamento, era a ideia.

**Seletor e mosaico foram removidos depois da decisão.** Variante que ninguém
usa não recebe teste, envelhece calada e quebra no dia em que alguém liga.
Restaram `carrossel` (padrão) e `fixa`, escolhidas por `app.vitrine.abertura`.

### O que foi construído para a escolha funcionar

| Defesa | Por quê |
| :--- | :--- |
| Botão de pausa explícito | Animação automática sem mecanismo de parada é violação de WCAG 2.2.2 |
| Indicadores clicáveis | Dão acesso direto ao slide que interessa, sem esperar a rotação |
| Clicar num indicador **pausa** | A partir do primeiro clique, quem manda é o visitante |
| Para com o ponteiro em cima ou foco dentro | É o defeito mais citado de carrossel, e o único sem contorno depois |
| Para com a aba em segundo plano | Não gira nem baixa imagem para ninguém |
| Nasce **parado** com `prefers-reduced-motion` | Quem configurou o sistema para menos movimento não pediu isso |
| `aria-live` desligado enquanto gira, `polite` quando pausado | Anunciar cada troca automática interromperia a leitura a cada poucos segundos; parado, a troca é ação do visitante e vale anunciar |
| Imagens seguintes adiantadas após o `load` | Com 6,5s de intervalo, esperar o slide aparecer para baixar mostraria quadro vazio em conexão lenta |
| Altura mínima na legenda | Legendas de tamanhos diferentes fariam o catálogo pular a cada troca |
| Sem JavaScript, o primeiro slide fica visível | A página continua inteira; só deixa de alternar |

Intervalo de **6,5 segundos**, e não os 5 iniciais: a legenda precisa caber
numa leitura tranquila.

### Captura de celular para cada tela

Este foi o problema que a comparação não mostrava e que só apareceu ao virar
padrão: **só o primeiro slide tinha versão de celular.** Os outros exibiriam a
captura de desktop encolhida para 375px — ilegível. Como a maior parte dos
visitantes chega pelo telefone, a prova deixaria de provar exatamente onde
mais importa.

O roteiro `scripts/capturar-telas.js` passou a capturar as três telas também
em viewport de celular, e cada `Tela` carrega os dois arquivos.

### A variante é configuração, não parâmetro de URL

`?abertura=...` criaria endereços diferentes com o mesmo conteúdo, que
buscador trata como página duplicada. A escolha vive em
`app.vitrine.abertura` (`carrossel` ou `fixa`).

### Dois defeitos que só apareceram olhando a tela

**Os indicadores não pintavam nada.** Para dar 44px de área de toque numa
barra de 3px, usei `padding: 20px 0` — mas o CSS tem `box-sizing: border-box`
global, então a altura de 3px passou a *incluir* os 40px de preenchimento, o
conteúdo ficou com altura negativa e a barra sumiu. O clique continuava
funcionando: os testes de comportamento passavam com o controle invisível.
Corrigido com `box-sizing: content-box` naquele elemento.

**Colisão de nome de classe com o logotipo.** A marca é
`Drlog<span class="ponto">.</span>`, e o indicador do carrossel também se
chamava `.ponto`. O CSS do carrossel estava sendo aplicado ao ponto final da
marca — com preenchimento e fundo, deixando uma faixa clara atrás de "Drlog."
no cabeçalho de todas as páginas. Eu tinha visto o artefato em capturas
anteriores e atribuído ao cabeçalho fixo.

O indicador passou a ser `.carrossel-ponto`. Num CSS global, nome genérico
como `ponto` é questão de tempo até colidir.

### Ícone de aba

Faltava favicon: a aba do navegador aparecia com o ícone genérico. Numa página
cuja função é transmitir confiança, isso é sinal de coisa inacabada. Foi
criado um "D" em serifa, cor de papel sobre couro queimado — a mesma paleta do
site — em SVG, ICO e ícone de toque.

### Verificação feita

| Cenário | Resultado |
| :--- | :--- |
| Gira sozinho | passa do slide 0 para o 1 em 6,5s |
| Ponteiro sobre a imagem | para enquanto estiver em cima |
| Botão de pausa | para, mantém parado, e o rótulo vira "Continuar" |
| Indicador clicado | vai ao slide certo **e** pausa |
| `prefers-reduced-motion` | nasce parado; após 7,5s continua no slide 0 |
| Sem JavaScript | um slide visível, página inteira |
| `aria-live` | `off` girando, `polite` pausado |
| Celular, slides 1 / 2 / 3 | usa a captura de celular de cada tela |
| Imagens dos slides seguintes | já carregadas quando a rotação chega nelas |
| Indicador | 30×43 de alvo de toque, barra de 3px visível |
| Ponto do logotipo | sem estilo do carrossel |
| Erros de console | nenhum |
| `/`, `/sistemas/styllos`, `/login`, `/painel` | 200, 200, 200, 302 |
| `favicon.ico`, `favicon.svg`, `apple-touch-icon.png` | 200 |

### O que continua valendo como risco

No celular a captura é alta, e **os controles ficam abaixo da dobra**. O
mecanismo de pausa existe — exigência de acessibilidade cumprida — mas quem
não rolar não vai encontrá-lo. É consequência direta de ter rotação
automática numa imagem vertical, e não tem solução boa sem diminuir a captura
a ponto de prejudicar a leitura.

Vale reavaliar a abertura quando existir o segundo sistema: aí a rotação passa
a ser de produtos, que era a intenção original, e o argumento muda.


---

## 15. O carrossel virou de sistemas

Reestruturação pedida depois de ver o carrossel de telas funcionando. Três
mudanças no cabeçalho e na abertura, e uma na natureza do carrossel.

### O que mudou

| Antes | Agora |
| :--- | :--- |
| Marca com a frase "sistemas de ordem de serviço" ao lado | Só a marca, em corpo 40px (era 27px) |
| Abertura em grade assimétrica: texto à esquerda, uma captura à direita | Texto de posicionamento em faixa no topo; abaixo, os sistemas |
| Carrossel de **telas** do Styllus, dentro de uma coluna | Carrossel de **sistemas**, cada um no envelope inteiro |
| — | Dentro de cada sistema, setas para navegar as capturas dele |

Cada slide passou a ser um envelope completo: nome, resumo, preço e botão de
um lado; a galeria de capturas daquele sistema do outro, com legenda e
contador.

### Duas camadas, com donos diferentes

A de fora — os sistemas — troca sozinha. A de dentro — as imagens de um
sistema — é do visitante, pelas setas.

**Duas rotações automáticas no mesmo lugar competiriam entre si** e nenhuma
seria acompanhável. E usar as setas pausa a de fora: olhar as imagens de um
sistema é sinal de interesse nele, e seria o pior momento possível para a
página trocar de assunto sozinha.

### As vagas "em breve"

`app.vitrine.vagas-em-breve` (padrão **2**) acrescenta slides reservados
depois dos sistemas reais, para que o carrossel possa ser visto funcionando
com mais de um item antes de o segundo produto existir.

Elas **não carregam nome nem descrição de produto**. Anunciar um sistema que
ainda não existe é promessa, e promessa numa página de venda é o primeiro
passo para a vitrine deixar de ser confiável. O slide diz apenas "Em breve",
com moldura hachurada vazia na mesma proporção das capturas.

**Devem ir a zero quando o segundo produto existir.** Uma vitrine com mais
vaga vazia que sistema diz mais sobre o que falta do que sobre o que há.

### Decisões de detalhe que vieram de olhar a tela

**O texto de posicionamento encolheu.** Na primeira montagem ele ocupava
metade da tela e empurrava o carrossel — a coisa que a reestruturação
existia para destacar — para fora da primeira dobra. Virou uma linha só, e o
primeiro sistema passou a começar a 328px do topo.

**As setas mudaram de cor.** A primeira versão era escura e translúcida, por
cima da captura. Como as telas do Styllus são escuras, os botões sumiam
dentro delas e liam como falha de renderização. Na cor do papel, montadas
metade para fora da moldura, ficam claramente do lado da página — não da
imagem.

**A vaga reservada ganhou um rodapé invisível.** Sem ele, o carrossel
encolhia 51px ao passar por uma vaga — exatamente a altura da legenda e do
contador que só os sistemas reais têm — e a página pulava a cada volta.
Medido: variação caiu de 51px para 10px.

### Verificação feita

| Cenário | Resultado |
| :--- | :--- |
| Sistemas giram sozinhos e o ciclo fecha | 0 → 1 → 2 → 0 |
| Seta direita / esquerda | troca a imagem, atualiza contador e legenda |
| Setas dão a volta | da última para a primeira |
| Usar as setas | pausa o carrossel de sistemas |
| Teclado (seta focada + ArrowRight) | avança a imagem |
| Altura entre slides | variação de 10px |
| Vaga "em breve" | sem preço, sem botão, moldura vazia |
| Celular | captura de celular em cada imagem, setas nas bordas |
| `prefers-reduced-motion` | nasce parado |
| Sem JavaScript | um sistema e uma imagem visíveis |
| Texto alternativo em toda imagem | presente |
| Erros de console | nenhum |


---

## 16. A abertura acompanha o carrossel

A faixa de texto acima do carrossel era fixa: falava do Styllus enquanto a
imagem já podia estar mostrando outro sistema. Ela passou para **dentro do
slide** — cada sistema traz o seu carimbo, a sua chamada e o seu texto.

| Slide | Carimbo | Chamada |
| :--- | :--- | :--- |
| Styllus | Em produção desde agosto de 2026 *(verde)* | O cliente deixa. Você conserta. *O sistema avisa.* |
| Vaga reservada | Em construção *(cinza)* | O próximo sistema *está sendo feito.* |

O carimbo da vaga é cinza, e não verde: verde significa "em produção", e
dizer isso de algo que não existe seria informação errada num lugar onde o
visitante está decidindo se confia.

### O texto de posicionamento agora só aparece no primeiro slide

Consequência direta da mudança, e vale registrar: *"É para esse trabalho que
a Drlog faz software"* é uma frase sobre a **empresa**, não sobre o Styllus, e
agora ela sai de cena quando o carrossel avança.

Enquanto houver um sistema real e vagas reservadas, o efeito é pequeno. Com
três sistemas de verdade, o visitante pode nunca ler o que a Drlog é. Vale
reavaliar então — uma linha curta e fixa no topo resolveria sem voltar atrás
na mudança.

### Hierarquia de títulos

Com a chamada dentro do slide, a página passou a ter **três `<h1>`**
alternando — confuso para leitor de tela e para buscador, que veem os três.

Agora há **um `<h1>` só**, fora da vista mas indexado, descrevendo a página
inteira: *"Drlog — sistemas para negócios que trabalham com ordem de
serviço"*. As chamadas dos slides são `<h2>`, e o nome do sistema, `<h3>`.

O título oculto usa `clip-path`, e não `display:none` nem
`visibility:hidden` — estes dois o removeriam também do leitor de tela, que é
justamente quem precisa dele.

### Um defeito de ordem no CSS

`.carimbo-neutro` tinha sido escrito **antes** de `.carimbo` no arquivo. Mesma
especificidade: quem está por último vence, então o cinza nunca aparecia e a
vaga reservada exibia "Em construção" em verde. Movido para depois.

---

## 17. Onde se edita o quê

Perguntado diretamente, e vale ficar registrado.

### HTML

| Arquivo | O que tem |
| :--- | :--- |
| `templates/site/inicio.html` | Página inicial: `<h1>` oculto, catálogo, nota de rodapé do catálogo |
| `templates/site/_aberturas.html` | O carrossel: envelope de cada sistema, galeria, setas, controles |
| `templates/site/produto.html` | Página do produto: passos, mensagem do WhatsApp, "o que vem junto" |
| `templates/site/_partes.html` | Cabeçalho e rodapé, compartilhados |
| `templates/login.html`, `painel.html` | Área do assinante |
| `static/css/site.css` | Aparência da vitrine |
| `static/css/portal.css` | Aparência do painel |
| `static/js/abertura.js` | Comportamento do carrossel e da galeria |

### Texto dos slides e das capturas

Não está no HTML: vem do `SiteController`, nos métodos `slidesDe` e
`imagensDe`. Ali ficam carimbo, chamada, destaque, texto e a lista de
capturas de cada sistema.

Está em código, e não em tabela, pelo mesmo motivo já registrado na seção 13:
com um produto, tabela seria estrutura sem uso. **Ao chegar o terceiro,
vira dado.**

### CNPJ, telefone, endereço — nunca no HTML

Ficam em variáveis de ambiente, porque mudam por instalação e porque um CNPJ
escrito no template vai para o Git e para a imagem Docker.

**Em desenvolvimento:** copiar `.env.example` para `.env` e preencher. O
`.env` está no `.gitignore` e o `scripts/dev.sh` o lê sozinho.

**Em produção:** as mesmas variáveis, cadastradas no Coolify.

O `.env` é lido linha a linha, e não com `source`: o `source` executa o
arquivo como script, então um valor sem aspas com espaço — `Razão Social
LTDA` — viraria tentativa de rodar o comando `Social`. Exigir aspas em todo
valor seria uma armadilha silenciosa num arquivo editado à mão. Verificado
com valor acentuado, com espaço, com espaço em volta do `=`, com aspas e com
comentário.

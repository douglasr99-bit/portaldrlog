#!/usr/bin/env python3
"""
================================================================================
ASAAS SIMULADO — para desenvolvimento e teste local

Existe para que o fluxo de cobrança possa ser exercitado sem tocar no gateway
de verdade. Mas a razão de estar VERSIONADO é outra: ele guarda o que foi
aprendido sobre a API do Asaas na marra, depois que uma subida em produção
falhou por suposições erradas.

A lição que originou este arquivo:

    A primeira versão deste simulador foi escrita a partir das mesmas
    suposições do código que ele deveria testar. Ele CONCORDAVA com o código
    em vez de confrontá-lo — e por isso tudo passou localmente e quebrou no
    primeiro cliente real.

Cada comportamento estranho aqui embaixo é deliberado e corresponde ao Asaas
real. Ao mudar qualquer coisa, confira contra o sandbox antes.

Uso:  python3 scripts/simuladores/asaas.py          (escuta em 127.0.0.1:8097)
      APP_ASAAS_BASE_URL=http://127.0.0.1:8097/v3
      APP_ASAAS_CHAVE=CHAVE-ASAAS

Controles fora da API (não existem no Asaas, são para o teste dirigir):
      GET /__estado                    tudo o que está guardado
      GET /__status/{cobranca}/{novo}  muda a VERDADE de uma cobrança
      GET /__pagar_checkout/{id}       simula o cliente aprovando o cartão
================================================================================
"""
import json, re, datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CHAVE = 'CHAVE-ASAAS'
CLIENTES, ASSINATURAS, CHECKOUTS = {}, {}, {}
COBRANCAS = {}          # id -> dict, e este dict é A VERDADE
RECEBIDO, SEQ = [], [0]


def proximo_mes(iso):
    d = datetime.date.fromisoformat(iso[:10])
    return d.replace(year=d.year + (d.month == 12), month=d.month % 12 + 1).isoformat()


class Asaas(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, *a):
        pass

    def _resp(self, cod, corpo):
        b = json.dumps(corpo).encode()
        self.send_response(cod)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def _corpo(self):
        # O RestClient do Spring manda o corpo em chunks quando não sabe o
        # tamanho de antemão. Ler só o Content-Length perderia a requisição
        # inteira, e o sintoma seria um 400 sem explicação.
        te = (self.headers.get('Transfer-Encoding') or '').lower()
        if 'chunked' in te:
            partes = []
            while True:
                linha = self.rfile.readline().strip()
                if not linha:
                    continue
                n = int(linha.split(b';')[0], 16)
                if n == 0:
                    self.rfile.readline()
                    break
                partes.append(self.rfile.read(n))
                self.rfile.readline()
            return b''.join(partes)
        return self.rfile.read(int(self.headers.get('Content-Length') or 0))

    def _auth(self):
        RECEBIDO.append({'path': self.path,
                         'token': self.headers.get('access_token'),
                         'ua': self.headers.get('User-Agent')})
        return self.headers.get('access_token') == CHAVE

    def _negado(self):
        return self._resp(401, {'errors': [{'code': 'invalid_access_token',
                                            'description': 'A chave de API fornecida é inválida'}]})

    # ------------------------------------------------------------------
    def do_GET(self):
        if self.path == '/__estado':
            return self._resp(200, {'clientes': CLIENTES, 'assinaturas': ASSINATURAS,
                                    'checkouts': CHECKOUTS, 'cobrancas': COBRANCAS,
                                    'chamadas': RECEBIDO})

        m = re.match(r'/__status/([^/]+)/([^/]+)', self.path)
        if m:
            COBRANCAS[m.group(1)]['status'] = m.group(2)
            if m.group(2) in ('CONFIRMED', 'RECEIVED', 'RECEIVED_IN_CASH'):
                COBRANCAS[m.group(1)]['paymentDate'] = datetime.date.today().isoformat()
            return self._resp(200, COBRANCAS[m.group(1)])

        # O cliente aprovou o cartão no checkout. Como no Asaas real: nascem o
        # cliente e a assinatura, e a primeira cobrança fica PENDENTE para a
        # data pedida — nada é debitado agora. É isso que torna o teste grátis.
        m = re.match(r'/__pagar_checkout/([^/]+)', self.path)
        if m:
            ck = CHECKOUTS.get(m.group(1))
            if not ck:
                return self._resp(404, {'erro': 'checkout não existe'})
            SEQ[0] += 1; cid = f'cus_{SEQ[0]:06d}'
            CLIENTES[cid] = {'id': cid, 'name': 'Cliente do checkout', 'cpfCnpj': '11222333000181'}
            assin = ck.get('subscription') or {}
            primeiro = (assin.get('nextDueDate') or '')[:10]
            SEQ[0] += 1; sid = f'sub_{SEQ[0]:06d}'
            ASSINATURAS[sid] = {
                'id': sid, 'customer': cid, 'billingType': 'CREDIT_CARD',
                'value': ck['items'][0]['value'], 'cycle': assin.get('cycle'),
                'status': 'ACTIVE',
                # NÃO herda o externalReference do checkout. Confirmado contra o
                # sandbox: volta sempre nulo. Amarrar por aqui não funciona.
                'externalReference': None,
                # É ESTE o elo entre o checkout e o que ele criou.
                'checkoutSession': ck['id'],
                # Já aponta para o ciclo SEGUINTE assim que a primeira cobrança
                # é gerada. Quem usar isto como fim do teste dá um mês grátis
                # de brinde e mostra na tela uma data diferente da do débito.
                'nextDueDate': proximo_mes(primeiro)}
            SEQ[0] += 1; pid = f'pay_{SEQ[0]:06d}'
            COBRANCAS[pid] = {'id': pid, 'subscription': sid, 'customer': cid,
                              'value': ck['items'][0]['value'], 'status': 'PENDING',
                              'billingType': 'CREDIT_CARD', 'dueDate': primeiro,
                              'checkoutSession': ck['id'],
                              'invoiceUrl': f'https://asaas.exemplo/{pid}'}
            ck['status'] = 'PAID'
            ck['customer'] = cid
            return self._resp(200, ck)

        if not self._auth():
            return self._negado()

        # NÃO EXISTE GET PARA CHECKOUTS NO ASAAS. Só POST.
        #
        # Este 404 é o coração deste arquivo. A versão anterior respondia 200
        # aqui, porque quem a escreveu supôs o padrão REST — e foi essa
        # gentileza que deixou passar um código que quebrou em produção.
        # O Asaas real devolve 404 antes mesmo de conferir a chave.
        if self.path.startswith('/v3/checkouts'):
            return self._resp(404, {})

        if self.path.startswith('/v3/subscriptions?') or self.path == '/v3/subscriptions':
            cli = re.search(r'customer=([^&]+)', self.path)
            ses = re.search(r'checkoutSession=([^&]+)', self.path)
            achadas = [a for a in ASSINATURAS.values()
                       if (not cli or a.get('customer') == cli.group(1))
                       and (not ses or a.get('checkoutSession') == ses.group(1))]
            return self._resp(200, {'data': achadas, 'totalCount': len(achadas)})

        if self.path.startswith('/v3/customers'):
            doc = re.search(r'cpfCnpj=([0-9]+)', self.path)
            achados = [c for c in CLIENTES.values() if doc and c.get('cpfCnpj') == doc.group(1)]
            return self._resp(200, {'data': achados, 'totalCount': len(achados)})

        m = re.match(r'/v3/subscriptions/([^/?]+)/payments', self.path)
        if m:
            desta = [c for c in COBRANCAS.values() if c.get('subscription') == m.group(1)]
            return self._resp(200, {'data': desta, 'totalCount': len(desta)})

        m = re.match(r'/v3/payments/([^/?]+)', self.path)
        if m:
            c = COBRANCAS.get(m.group(1))
            return self._resp(200, c) if c else self._resp(404, {'errors': [{'description': 'não existe'}]})

        self._resp(404, {'errors': [{'description': 'rota desconhecida'}]})

    # ------------------------------------------------------------------
    def do_POST(self):
        corpo = json.loads(self._corpo() or b'{}')
        if not self._auth():
            return self._negado()

        if self.path == '/v3/customers':
            SEQ[0] += 1
            cid = f'cus_{SEQ[0]:06d}'
            CLIENTES[cid] = {'id': cid, **corpo}
            return self._resp(200, CLIENTES[cid])

        if self.path == '/v3/subscriptions':
            SEQ[0] += 1
            sid = f'sub_{SEQ[0]:06d}'
            ASSINATURAS[sid] = {'id': sid, 'status': 'ACTIVE',
                                'checkoutSession': None, **corpo}
            SEQ[0] += 1
            pid = f'pay_{SEQ[0]:06d}'
            COBRANCAS[pid] = {'id': pid, 'subscription': sid, 'customer': corpo.get('customer'),
                              'value': corpo.get('value'), 'status': 'PENDING',
                              'billingType': corpo.get('billingType'),
                              'dueDate': corpo.get('nextDueDate'),
                              'checkoutSession': None,
                              'invoiceUrl': f'https://asaas.exemplo/{pid}'}
            return self._resp(200, {**ASSINATURAS[sid], 'primeiraCobranca': pid})

        if self.path == '/v3/checkouts':
            SEQ[0] += 1
            ckid = f'chk_{SEQ[0]:06d}'
            CHECKOUTS[ckid] = {'id': ckid, 'status': 'ACTIVE',
                               'link': f'http://127.0.0.1:8097/__tela/{ckid}', **corpo}
            return self._resp(200, CHECKOUTS[ckid])

        m = re.match(r'/v3/payments/([^/?]+)/receiveInCash', self.path)
        if m:
            c = COBRANCAS.get(m.group(1))
            if not c:
                return self._resp(404, {'errors': [{'description': 'não existe'}]})
            c['status'] = 'RECEIVED_IN_CASH'
            c['paymentDate'] = corpo.get('paymentDate')
            return self._resp(200, c)

        self._resp(404, {'errors': [{'description': 'rota desconhecida'}]})

    # ------------------------------------------------------------------
    def do_DELETE(self):
        if not self._auth():
            return self._negado()
        m = re.match(r'/v3/subscriptions/([^/?]+)', self.path)
        if m:
            if not ASSINATURAS.pop(m.group(1), None):
                return self._resp(404, {'errors': [{'description': 'não existe'}]})
            # Cancelar no Asaas apaga junto as cobranças PENDENTES da
            # assinatura. Ninguém recebe boleto de assinatura cancelada.
            for pid in [p for p, c in COBRANCAS.items()
                        if c.get('subscription') == m.group(1) and c['status'] == 'PENDING']:
                COBRANCAS.pop(pid)
            return self._resp(200, {'deleted': True, 'id': m.group(1)})
        self._resp(404, {'errors': [{'description': 'rota desconhecida'}]})


if __name__ == '__main__':
    print('Asaas simulado em http://127.0.0.1:8097  (chave: %s)' % CHAVE)
    ThreadingHTTPServer(('127.0.0.1', 8097), Asaas).serve_forever()

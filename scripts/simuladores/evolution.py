#!/usr/bin/env python3
"""
================================================================================
EVOLUTION API SIMULADA — para desenvolvimento e teste local

Existe para que o provisionamento de WhatsApp possa ser exercitado sem criar
instâncias de verdade. Cada instância real consome memória da VPS, e um teste
descuidado deixa lixo que só se descobre quando o servidor aperta.

Está versionada pelo mesmo motivo do simulador do Asaas: ela guarda detalhes
do comportamento real que já custaram caro.

Uso:  python3 scripts/simuladores/evolution.py     (escuta em 127.0.0.1:8099)
      APP_EVOLUTION_BASE_URL=http://127.0.0.1:8099
      APP_EVOLUTION_CHAVE=CHAVE-EVO

⚠️ Atenção ao nome da variável: é APP_EVOLUTION_BASE_URL, não ..._URL. Com o
   nome errado o Spring usa o padrão do application.yml, que aponta para a
   Evolution DE PRODUÇÃO — e um teste local passa a criar instâncias reais.
   Já aconteceu; falhou fechado só porque a chave de teste era inválida.

Controles fora da API:
      GET /__recebido     tudo o que chegou, em ordem
      GET /__limpar       esquece o histórico
================================================================================
"""
import json, re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

INSTANCIAS = set()
RECEBIDO, CONTADOR = [], [0]


class Evolution(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, *a):
        pass

    def _responder(self, cod, corpo):
        b = json.dumps(corpo).encode()
        self.send_response(cod)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def _corpo(self):
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

    def do_GET(self):
        if self.path == '/__recebido':
            return self._responder(200, {'instancias': sorted(INSTANCIAS), 'chamadas': RECEBIDO})
        if self.path == '/__limpar':
            RECEBIDO.clear()
            return self._responder(200, {'limpo': True})

        m = re.match(r'/instance/connectionState/(.+)', self.path)
        if m:
            nome = m.group(1)
            RECEBIDO.append({'acao': 'status', 'instancia': nome,
                             'apikey': self.headers.get('apikey')})
            if nome not in INSTANCIAS:
                return self._responder(404, {'error': 'instance not found'})
            return self._responder(200, {'instance': {'instanceName': nome, 'state': 'open'}})

        self._responder(404, {'error': 'not found'})

    def do_POST(self):
        try:
            corpo = json.loads(self._corpo() or b'{}')
        except Exception as e:
            return self._responder(400, {'error': f'corpo ilegível: {e}'})

        if self.path == '/instance/create':
            nome = corpo.get('instanceName')
            RECEBIDO.append({'acao': 'criar', 'instancia': nome,
                             'apikey': self.headers.get('apikey')})
            if not nome:
                return self._responder(400, {'error': 'instanceName ausente'})
            INSTANCIAS.add(nome)
            CONTADOR[0] += 1
            # A Evolution v2 devolve a credencial DA INSTÂNCIA no campo "hash".
            # É ela que o sistema vendido usa depois — não a chave global, que
            # nunca deve sair do Portal.
            return self._responder(201, {'instance': {'instanceName': nome},
                                         'hash': f'TOKEN-{CONTADOR[0]}-DE-{nome}'})

        m = re.match(r'/message/sendText/(.+)', self.path)
        if m:
            RECEBIDO.append({'acao': 'enviar', 'instancia': m.group(1),
                             'apikey': self.headers.get('apikey'),
                             'numero': corpo.get('number'), 'texto': corpo.get('text')})
            return self._responder(201, {'key': {'id': 'SIMULADO'}})

        self._responder(404, {'error': 'not found'})

    def do_DELETE(self):
        m = re.match(r'/instance/delete/(.+)', self.path)
        if m:
            nome = m.group(1)
            RECEBIDO.append({'acao': 'apagar', 'instancia': nome,
                             'apikey': self.headers.get('apikey')})
            INSTANCIAS.discard(nome)
            return self._responder(200, {'apagada': nome, 'restam': sorted(INSTANCIAS)})
        self._responder(404, {'error': 'not found'})


if __name__ == '__main__':
    print('Evolution simulada em http://127.0.0.1:8099')
    ThreadingHTTPServer(('127.0.0.1', 8099), Evolution).serve_forever()

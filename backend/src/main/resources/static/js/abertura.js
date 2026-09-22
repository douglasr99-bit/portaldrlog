/**
 * ============================================================================
 * ABERTURA DA VITRINE
 *
 * Duas camadas, com donos diferentes:
 *
 *   - o carrossel de SISTEMAS troca sozinho;
 *   - a galeria de imagens DENTRO de cada sistema é navegada pelo visitante,
 *     com as setas.
 *
 * Duas rotações automáticas no mesmo lugar competiriam entre si e nenhuma
 * seria acompanhável. Usar as setas pausa a de fora: a partir do primeiro
 * gesto, quem conduz é o visitante.
 *
 * Sem JavaScript, aparecem o primeiro sistema e a primeira imagem dele. A
 * página continua inteira; só deixa de alternar.
 * ============================================================================
 */
(function () {
  'use strict';

  const menosMovimento = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  // ------------------------------------------------------------------
  // Galerias: as imagens de cada sistema
  // ------------------------------------------------------------------
  document.querySelectorAll('.sistema-galeria[data-galeria]').forEach(galeria => {
    const figuras  = [...galeria.querySelectorAll('.galeria-figura')];
    const legendas = [...galeria.querySelectorAll('.legenda-item')];
    const contador = galeria.querySelector('.galeria-atual');
    const anterior = galeria.querySelector('.galeria-seta-anterior');
    const proxima  = galeria.querySelector('.galeria-seta-proxima');
    if (figuras.length < 2 || !anterior || !proxima) return;

    let atual = 0;

    const mostrar = (i) => {
      atual = (i + figuras.length) % figuras.length;
      figuras.forEach((f, n)  => f.classList.toggle('ativa', n === atual));
      legendas.forEach((l, n) => l.classList.toggle('ativa', n === atual));
      if (contador) contador.textContent = String(atual + 1);
    };

    const andar = (passo) => {
      mostrar(atual + passo);
      // Olhar as imagens de um sistema é sinal de interesse nele. Deixar o
      // carrossel levar o visitante embora no meio disso seria o pior
      // momento possível para trocar de assunto.
      document.dispatchEvent(new CustomEvent('vitrine:assumiuControle'));
    };

    anterior.addEventListener('click', () => andar(-1));
    proxima .addEventListener('click', () => andar(+1));

    galeria.addEventListener('keydown', (e) => {
      if (e.key === 'ArrowLeft')  { e.preventDefault(); andar(-1); }
      if (e.key === 'ArrowRight') { e.preventDefault(); andar(+1); }
    });

    // As imagens seguintes começam a carregar depois que a página monta: a
    // seta responde na hora, sem esperar download.
    const adiantar = () => figuras.slice(1).forEach(f => {
      const img = f.querySelector('img');
      if (img) { img.loading = 'eager'; img.decode?.().catch(() => {}); }
    });
    if (document.readyState === 'complete') adiantar();
    else window.addEventListener('load', adiantar, { once: true });
  });

  // ------------------------------------------------------------------
  // Carrossel de sistemas
  // ------------------------------------------------------------------
  const carrossel = document.querySelector('.carrossel');
  if (!carrossel) return;

  const palco    = carrossel.querySelector('.carrossel-palco');
  const slides   = [...carrossel.querySelectorAll('.carrossel-slide')];
  const pontos   = [...carrossel.querySelectorAll('.carrossel-ponto')];
  const controles = carrossel.querySelector('.carrossel-controles');
  if (slides.length < 2 || !controles) return;

  const btnPausa = controles.querySelector('.carrossel-pausa');
  const rotulo   = btnPausa.querySelector('.pausa-texto');
  const intervalo = Number(carrossel.dataset.intervalo) || 7000;

  let atual = 0;
  let timer = null;
  let pausado = menosMovimento;

  const ir = (i) => {
    atual = (i + slides.length) % slides.length;
    slides.forEach((s, n) => s.classList.toggle('ativo', n === atual));
    pontos.forEach((p, n) => {
      p.classList.toggle('ativo', n === atual);
      p.setAttribute('aria-current', n === atual ? 'true' : 'false');
    });
  };

  const tocar = () => { if (!pausado && !timer) timer = setInterval(() => ir(atual + 1), intervalo); };
  const parar = () => { clearInterval(timer); timer = null; };

  /**
   * Enquanto gira sozinho, aria-live fica desligado: anunciar cada troca
   * interromperia a leitura a cada poucos segundos sem ninguém ter pedido.
   * Parado, a troca é ação do visitante — e aí vale anunciar.
   */
  const definirPausa = (valor) => {
    pausado = valor;
    valor ? parar() : tocar();
    btnPausa.classList.toggle('pausado', valor);
    btnPausa.setAttribute('aria-label', valor ? 'Retomar a troca automática'
                                              : 'Pausar a troca automática');
    rotulo.textContent = valor ? ' Continuar' : ' Pausar';
    palco.setAttribute('aria-live', valor ? 'polite' : 'off');
  };

  btnPausa.addEventListener('click', () => definirPausa(!pausado));

  pontos.forEach(p => p.addEventListener('click', () => {
    ir(Number(p.dataset.ir));
    definirPausa(true);
  }));

  document.addEventListener('vitrine:assumiuControle', () => definirPausa(true));

  // Com o ponteiro em cima, ou foco dentro, a troca espera. Começar a ler e
  // ver o conteúdo virar no meio é o defeito mais citado de carrossel.
  carrossel.addEventListener('mouseenter', parar);
  carrossel.addEventListener('mouseleave', () => tocar());
  carrossel.addEventListener('focusin',  parar);
  carrossel.addEventListener('focusout', () => tocar());

  document.addEventListener('visibilitychange', () => document.hidden ? parar() : tocar());

  definirPausa(pausado);
})();

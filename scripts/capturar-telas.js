/**
 * ============================================================================
 * CAPTURA DAS TELAS DO STYLLUS PARA A VITRINE
 *
 * As imagens de backend/src/main/resources/static/img/ não são mockups: são
 * capturas do sistema real, rodando contra um PostgreSQL, com dados de
 * exemplo. Este roteiro é como elas foram feitas.
 *
 * Está versionado porque, quando a interface do Styllus mudar, as capturas
 * ficam velhas — e sem o roteiro ninguém lembra como refazê-las.
 *
 * Pré-requisitos:
 *   1. Um PostgreSQL vazio para o Styllus
 *   2. O Styllus rodando contra ele, com dados de exemplo cadastrados e
 *      distribuídos pelas três colunas do Kanban
 *   3. As dependências instaladas:  cd scripts && npm install
 *      (usa o Chrome já instalado na máquina; não baixa navegador)
 *
 * Uso:
 *   cd scripts && node capturar-telas.js <destino> [url] [usuario] [senha]
 *
 * Depois, otimizar para a web:
 *   magick origem.png -resize 1800x -strip destino.png
 *   optipng -o2 destino.png
 *   cwebp -q 82 destino.png -o destino.webp
 * ============================================================================
 */
const puppeteer = require('puppeteer-core');

const OUT     = process.argv[2];
const BASE    = process.argv[3] || 'http://localhost:8081';
const USUARIO = process.argv[4] || 'styllos';
const SENHA   = process.argv[5] || 'demo1234';

if (!OUT) {
  console.error('uso: node scripts/capturar-telas.js <destino> [url] [usuario] [senha]');
  process.exit(1);
}

(async () => {
  const browser = await puppeteer.launch({
    executablePath: process.env.CHROME_PATH || '/usr/bin/google-chrome',
    headless: 'new',
    args: ['--no-sandbox', '--disable-dev-shm-usage'],
  });

  const page = await browser.newPage();
  // 2x: as capturas aparecem grandes na vitrine e em tela retina.
  await page.setViewport({ width: 1440, height: 900, deviceScaleFactor: 2 });

  await page.goto(`${BASE}/login.html`, { waitUntil: 'networkidle0' });
  await page.type('#inputUsuario', USUARIO);
  await page.type('#inputSenha', SENHA);
  await Promise.all([
    page.click('#btnEntrar'),
    page.waitForNavigation({ waitUntil: 'networkidle0' }).catch(() => {}),
  ]);

  await page.goto(`${BASE}/index.html`, { waitUntil: 'networkidle0' });
  await espera(2500);

  const irPara = async (pagina) => {
    await page.evaluate(id => document.querySelector(`.nav-item[data-page="${id}"]`)?.click(), pagina);
    await espera(1400);
  };
  const capturar = async (nome) => {
    await page.screenshot({ path: `${OUT}/${nome}.png` });
    console.log('  ->', nome);
  };

  await capturar('kanban');
  await irPara('dashboard'); await capturar('dashboard');
  await irPara('cadastro');  await capturar('cadastro');
  await irPara('clientes');  await capturar('clientes');

  // O celular não é um extra: é como o dono da loja realmente olha o sistema,
  // e é a imagem que a vitrine usa em telas pequenas.
  //
  // Precisa ser TODA tela que a vitrine exibe, não só a primeira: a abertura
  // alterna entre elas, e uma captura de desktop encolhida para 375px é
  // ilegível — a prova deixa de provar exatamente onde estão a maioria dos
  // visitantes.
  await page.setViewport({ width: 390, height: 844, deviceScaleFactor: 3, isMobile: true });
  await page.goto(`${BASE}/index.html`, { waitUntil: 'networkidle0' });
  await espera(2500);
  await capturar('kanban-celular');
  await irPara('dashboard'); await capturar('dashboard-celular');
  await irPara('cadastro');  await capturar('cadastro-celular');

  await browser.close();
})().catch(e => { console.error('FALHOU:', e.message); process.exit(1); });

function espera(ms) { return new Promise(r => setTimeout(r, ms)); }

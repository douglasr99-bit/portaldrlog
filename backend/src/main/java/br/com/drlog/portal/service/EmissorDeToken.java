package br.com.drlog.portal.service;

import br.com.drlog.portal.model.*;
import br.com.drlog.portal.repository.ChaveJwtRepository;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.*;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.*;

/**
 * ============================================================================
 * EMISSÃO DO TOKEN DE ACESSO
 *
 * O contrato entre o Portal e cada sistema vendido. O Portal assina com a
 * chave privada; o sistema verifica com a pública, buscada do JWKS.
 *
 * O token NUNCA carrega segredo. Ele passa pelo navegador do usuário — o que
 * estiver dentro dele é público para quem o tiver em mãos. Identidade e
 * autorização entram; chave de instância de WhatsApp, não.
 * ============================================================================
 */
@Service
public class EmissorDeToken {

    private static final Logger log = LoggerFactory.getLogger(EmissorDeToken.class);

    /**
     * Vida do token: um pulo do Portal para o sistema, não uma sessão.
     *
     * Sessenta segundos cobrem a latência do redirecionamento com folga e
     * deixam uma janela curta demais para alguém aproveitar um token
     * capturado — que ainda por cima é de uso único do outro lado.
     */
    private static final long VALIDADE_SEGUNDOS = 60;

    private final ChaveJwtRepository chaves;
    private final String emissor;

    public EmissorDeToken(ChaveJwtRepository chaves,
                          @Value("${app.portal.url:http://localhost:8082}") String portalUrl) {
        this.chaves = chaves;
        this.emissor = portalUrl.replaceAll("/+$", "");
    }

    /**
     * Assina um token para abrir um sistema.
     *
     * @param assinatura a assinatura que autoriza o acesso
     * @param conta      quem está entrando
     * @param papel      o que essa pessoa é dentro do assinante
     */
    public String emitir(Assinatura assinatura, Conta conta, Papel papel) {
        ChaveJwt chave = chaveAtiva();
        Instant agora = Instant.now();

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(emissor)
                // Verificado do outro lado: um token emitido para outro
                // produto é rejeitado mesmo sendo válido e assinado. Sem isso,
                // o token de qualquer sistema abriria qualquer outro.
                .audience(assinatura.getProduto().getCodigo())
                .subject(conta.getId().toString())
                .claim("tenant_id", assinatura.getTenant().getCodigo())
                .claim("loja_nome", assinatura.getTenant().getNome())
                .claim("produto",   assinatura.getProduto().getCodigo())
                .claim("plano",     assinatura.getPlano().getCodigo())
                .claim("papel",     papel.name())
                .claim("email",     conta.getEmail())
                .claim("nome",      conta.getNome())
                // Identificador único: é o que permite ao sistema recusar a
                // segunda apresentação do mesmo token.
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(agora))
                .expirationTime(Date.from(agora.plusSeconds(VALIDADE_SEGUNDOS)))
                .build();

        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(chave.getKid()).build(),
                    claims);
            jwt.sign(new RSASSASigner(privada(chave)));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Falha ao assinar o token de acesso", e);
        }
    }

    /**
     * O conjunto de chaves públicas, como os sistemas o consomem.
     *
     * Garante a chave antes de listar. Sem isso, um JWKS consultado antes da
     * primeira emissão devolve uma lista vazia — e o sistema vendido, que
     * guarda esse resultado em cache, passa a recusar tokens legítimos até o
     * cache expirar. Falha difícil de diagnosticar: o token está certo, a
     * assinatura está certa, e a rejeição vem de um conjunto vazio guardado
     * minutos antes.
     */
    public Map<String, Object> jwks() {
        chaveAtiva();
        List<JWK> lista = chaves.findAllByOrderByCriadaEmDesc().stream()
                .map(c -> (JWK) new RSAKey.Builder(publica(c))
                        .keyID(c.getKid())
                        .keyUse(KeyUse.SIGNATURE)
                        .algorithm(JWSAlgorithm.RS256)
                        .build())
                .toList();
        return new JWKSet(lista).toJSONObject();
    }

    /**
     * A chave ativa, criada na primeira vez que for preciso.
     *
     * Fica no banco, e não em memória: gerada a cada reinício, ela
     * invalidaria todos os tokens em trânsito e, pior, mudaria o JWKS sem
     * aviso — o sistema vendido passaria a recusar tokens legítimos até
     * recarregar o cache.
     */
    @Transactional
    public ChaveJwt chaveAtiva() {
        return chaves.findByAtivaTrue().orElseGet(this::gerar);
    }

    private ChaveJwt gerar() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair par = g.generateKeyPair();

            ChaveJwt chave = chaves.save(ChaveJwt.builder()
                    .kid(UUID.randomUUID().toString())
                    .privada(Base64.getEncoder().encodeToString(par.getPrivate().getEncoded()))
                    .publica(Base64.getEncoder().encodeToString(par.getPublic().getEncoded()))
                    .ativa(true)
                    .build());

            log.info("Chave de assinatura criada (kid {}). O JWKS já a publica.", chave.getKid());
            return chave;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA indisponível nesta JVM", e);
        }
    }

    private RSAPrivateKey privada(ChaveJwt c) {
        try {
            return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(c.getPrivada())));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Chave privada ilegível (kid " + c.getKid() + ")", e);
        }
    }

    private RSAPublicKey publica(ChaveJwt c) {
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(c.getPublica())));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Chave pública ilegível (kid " + c.getKid() + ")", e);
        }
    }
}

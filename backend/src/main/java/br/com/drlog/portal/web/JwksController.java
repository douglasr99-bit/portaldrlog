package br.com.drlog.portal.web;

import br.com.drlog.portal.service.EmissorDeToken;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * A chave pública com que os sistemas vendidos verificam os tokens.
 *
 * É público por definição — é a chave pública. O que ela permite é conferir
 * assinatura, nunca produzir uma.
 */
@RestController
public class JwksController {

    private final EmissorDeToken emissor;

    public JwksController(EmissorDeToken emissor) {
        this.emissor = emissor;
    }

    @GetMapping("/.well-known/jwks.json")
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                // Cache curto: o sistema vendido guarda a chave e não bate
                // aqui a cada token. Cinco minutos é o bastante para uma
                // rotação se propagar sem que ninguém precise reiniciar nada.
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(emissor.jwks());
    }
}

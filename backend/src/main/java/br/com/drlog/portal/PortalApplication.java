package br.com.drlog.portal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * @EnableAsync existe pelo webhook: ele grava o evento, responde 2xx e
 * processa fora da requisição. A fila do Asaas é sequencial e para após 15
 * falhas seguidas — segurar a resposta atrasaria os eventos de todos os
 * assinantes.
 */
@EnableAsync
@SpringBootApplication
public class PortalApplication {
    public static void main(String[] args) {
        SpringApplication.run(PortalApplication.class, args);
    }
}

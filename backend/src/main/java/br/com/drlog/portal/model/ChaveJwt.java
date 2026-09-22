package br.com.drlog.portal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** Par de chaves com que o Portal assina os tokens de acesso. */
@Entity
@Table(name = "chaves_jwt")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
@EqualsAndHashCode(of = "kid")
public class ChaveJwt {

    /** Identificador que vai no cabeçalho do token e no JWKS. */
    @Id
    private String kid;

    /** PKCS#8, em base64. */
    @Column(nullable = false, length = 4000)
    private String privada;

    /** X.509, em base64. */
    @Column(nullable = false, length = 2000)
    private String publica;

    @Column(nullable = false)
    @Builder.Default
    private boolean ativa = true;

    @Column(name = "criada_em", nullable = false)
    private Instant criadaEm;

    @PrePersist
    private void aoCriar() {
        if (criadaEm == null) criadaEm = Instant.now();
    }
}

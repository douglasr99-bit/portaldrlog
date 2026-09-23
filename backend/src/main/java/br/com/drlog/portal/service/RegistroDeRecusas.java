package br.com.drlog.portal.service;

import br.com.drlog.portal.model.CadastroRecusado;
import br.com.drlog.portal.repository.CadastroRecusadoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Grava as recusas de cadastro numa transação própria.
 *
 * Componente separado, e não um método do CadastroService, por dois motivos
 * que se somam:
 *
 *  - REQUIRES_NEW só vale através do proxy do Spring; uma chamada a si mesmo
 *    o ignoraria em silêncio;
 *  - a recusa é lançada como exceção logo depois de registrada, e a exceção
 *    desfaz a transação em curso — levando junto o registro.
 *
 * O resultado desse detalhe era a tabela ficar sempre vazia, justamente a
 * tabela que existe para tornar visível quando a trava barra demais.
 */
@Service
public class RegistroDeRecusas {

    private final CadastroRecusadoRepository recusas;

    public RegistroDeRecusas(CadastroRecusadoRepository recusas) {
        this.recusas = recusas;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registrar(String ip, String email, String motivo) {
        recusas.save(CadastroRecusado.builder().ip(ip).email(email).motivo(motivo).build());
    }
}

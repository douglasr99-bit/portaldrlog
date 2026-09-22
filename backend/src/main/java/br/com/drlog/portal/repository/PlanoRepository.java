package br.com.drlog.portal.repository;

import br.com.drlog.portal.model.Plano;
import br.com.drlog.portal.model.Produto;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

public interface PlanoRepository extends Repository<Plano, UUID> {

    List<Plano> findByProdutoAndAtivoTrueOrderByPrecoCentavosAsc(Produto produto);
}

package br.com.drlog.portal.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;

/**
 * ============================================================================
 * DADOS DA EMPRESA
 *
 * Vão no rodapé da vitrine. Comprador brasileiro de pequeno negócio confere
 * CNPJ e telefone antes de assinar qualquer coisa — é parte do que faz a
 * página parecer de gente.
 *
 * Nenhum valor tem padrão de propósito. Um CNPJ de exemplo que vaze para
 * produção é pior que rodapé incompleto: é informação falsa sobre quem está
 * cobrando. A página omite o que não estiver preenchido e a aplicação avisa
 * no log o que falta.
 * ============================================================================
 */
@Component
@ConfigurationProperties(prefix = "app.empresa")
public class Empresa {

    private static final Logger log = LoggerFactory.getLogger(Empresa.class);

    private String razaoSocial;
    private String cnpj;
    private String endereco;
    /** Só dígitos, com DDI: 5511999998888. */
    private String whatsapp;
    private String email;

    @PostConstruct
    void avisarPendencias() {
        List<String> faltando = new ArrayList<>();
        if (vazio(razaoSocial)) faltando.add("APP_EMPRESA_RAZAO_SOCIAL");
        if (vazio(cnpj))        faltando.add("APP_EMPRESA_CNPJ");
        if (vazio(endereco))    faltando.add("APP_EMPRESA_ENDERECO");
        if (vazio(whatsapp))    faltando.add("APP_EMPRESA_WHATSAPP");

        if (!faltando.isEmpty()) {
            log.warn("Rodapé da vitrine incompleto — não definidas: {}. "
                   + "A página omite o que falta.", String.join(", ", faltando));
        }
    }

    private boolean vazio(String s) { return s == null || s.isBlank(); }

    /**
     * Texto em branco vira nulo.
     *
     * Não é preciosismo: para o Thymeleaf, a string vazia é um valor
     * verdadeiro — só null é falso. Sem esta normalização, uma variável de
     * ambiente não preenchida faz o th:if passar e o rodapé exibir "CNPJ"
     * sem número nenhum. Num rodapé que existe justamente para provar que há
     * uma empresa de verdade por trás, esse rótulo pelado faz o contrário do
     * que deveria.
     */
    private static String ouNulo(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** Link de conversa, montado a partir do número. */
    public String getWhatsappLink() {
        return vazio(whatsapp) ? null : "https://wa.me/" + whatsapp.replaceAll("\\D", "");
    }

    /** O número como se lê: (11) 99999-8888. */
    public String getWhatsappExibicao() {
        if (vazio(whatsapp)) return null;
        String d = whatsapp.replaceAll("\\D", "");
        if (d.startsWith("55")) d = d.substring(2);
        if (d.length() == 11) return "(%s) %s-%s".formatted(d.substring(0,2), d.substring(2,7), d.substring(7));
        if (d.length() == 10) return "(%s) %s-%s".formatted(d.substring(0,2), d.substring(2,6), d.substring(6));
        return whatsapp;
    }

    public String getRazaoSocial() { return razaoSocial; }
    public void setRazaoSocial(String v) { this.razaoSocial = ouNulo(v); }
    public String getCnpj() { return cnpj; }
    public void setCnpj(String v) { this.cnpj = ouNulo(v); }
    public String getEndereco() { return endereco; }
    public void setEndereco(String v) { this.endereco = ouNulo(v); }
    public String getWhatsapp() { return whatsapp; }
    public void setWhatsapp(String v) { this.whatsapp = ouNulo(v); }
    public String getEmail() { return email; }
    public void setEmail(String v) { this.email = ouNulo(v); }
}

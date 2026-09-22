package br.com.drlog.portal.model;

/** O que uma conta pode fazer sobre um assinante. */
public enum Papel {
    /** Responde pela assinatura: contrata, cancela e gerencia quem acessa. */
    dono,
    /** Usa os sistemas contratados, sem mexer em cobrança. */
    operador
}

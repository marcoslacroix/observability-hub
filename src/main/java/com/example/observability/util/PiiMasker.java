package com.example.observability.util;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Utilitário para mascaramento e sanitização de dados pessoais (PII)
 * em conformidade com as diretrizes da LGPD (Lei 13.709/2018).
 */
public final class PiiMasker {

    private PiiMasker() {
    }

    /**
     * Mascara endereços de e-mail preservando apenas o caractere inicial e final do identificador
     * e o domínio.
     * Exemplo: usuario@example.com -> u***o@example.com
     * Exemplo: ab@example.com -> a*@example.com
     */
    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "[vazio]";
        }
        int atIndex = email.indexOf('@');
        if (atIndex <= 0) {
            return "***";
        }
        String localPart = email.substring(0, atIndex);
        String domain = email.substring(atIndex);

        if (localPart.length() <= 1) {
            return "*".repeat(localPart.length()) + domain;
        } else if (localPart.length() <= 2) {
            return localPart.charAt(0) + "*" + domain;
        } else {
            return localPart.charAt(0) + "***" + localPart.charAt(localPart.length() - 1) + domain;
        }
    }

    /**
     * Mascara nomes de pessoas mantendo apenas a inicial de cada termo.
     * Exemplo: Marcos Lacroix -> M*** L***
     */
    public static String maskName(String name) {
        if (name == null || name.isBlank()) {
            return "[vazio]";
        }
        return Arrays.stream(name.trim().split("\\s+"))
                .map(part -> {
                    if (part.length() <= 1) {
                        return part;
                    }
                    return part.charAt(0) + "***";
                })
                .collect(Collectors.joining(" "));
    }
}

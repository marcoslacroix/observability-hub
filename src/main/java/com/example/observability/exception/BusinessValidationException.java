package com.example.observability.exception;

/**
 * Exceção de validação de negócio otimizada para alta vazão (sem custo de fillInStackTrace na JVM).
 */
public class BusinessValidationException extends RuntimeException {

    public BusinessValidationException(String message) {
        // enableSuppression = false, writableStackTrace = false -> evita caminhar pela pilha da JVM em erros esperados de validação
        super(message, null, false, false);
    }

}

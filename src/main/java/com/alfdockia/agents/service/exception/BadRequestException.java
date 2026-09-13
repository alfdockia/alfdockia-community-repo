/*
 * Copyright (c) 2026 cparedes. Todos los derechos reservados.
 */
package com.alfdockia.agents.service.exception;

/**
 * Excepcion de dominio que conserva un codigo funcional para la respuesta JSON.
 */
public class BadRequestException extends RuntimeException {
    private final String code;

    public BadRequestException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}

package com.jvuln.controller.support;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ApiResponseFactory {

    private static final String ERROR_KEY = "error";

    private ApiResponseFactory() {
    }

    public static ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.badRequest().body(errorBody(message));
    }

    public static ResponseEntity<Map<String, String>> conflict(String message) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorBody(message));
    }

    public static ResponseEntity<Map<String, String>> internalServerError(String message) {
        return ResponseEntity.internalServerError().body(errorBody(message));
    }

    public static Map<String, String> errorBody(String message) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put(ERROR_KEY, message);
        return body;
    }
}

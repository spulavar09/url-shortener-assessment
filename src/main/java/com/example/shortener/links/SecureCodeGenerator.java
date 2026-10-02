package com.example.shortener.links;

import java.security.SecureRandom;

import org.springframework.stereotype.Component;

@Component
public final class SecureCodeGenerator implements CodeGenerator {
    private static final char[] ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private final SecureRandom random = new SecureRandom();

    @Override
    public String next() {
        char[] code = new char[10];
        for (int i = 0; i < code.length; i++) code[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        return new String(code);
    }
}

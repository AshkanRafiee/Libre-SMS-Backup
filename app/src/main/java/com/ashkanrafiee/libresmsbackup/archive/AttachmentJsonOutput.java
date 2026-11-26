package com.ashkanrafiee.libresmsbackup.archive;

import android.util.Base64;
import android.util.Base64OutputStream;
import android.util.JsonWriter;

import java.io.FilterWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Lets JsonWriter handle punctuation while an already staged Base64 value is written in chunks. */
final class AttachmentJsonOutput extends FilterWriter {
    private StringBuilder valueSyntax;

    AttachmentJsonOutput(Writer output) {
        super(output);
    }

    void writeAttachment(JsonWriter json, InputStream file) throws IOException {
        json.name("dataBase64");
        valueSyntax = new StringBuilder();
        String syntax;
        try {
            json.value("");
            syntax = valueSyntax.toString();
        } finally {
            valueSyntax = null;
        }
        if (!syntax.endsWith("\"\"")) throw new IOException("cannot write backup attachment");
        out.write(syntax, 0, syntax.length() - 1);
        byte[] buffer = new byte[24 * 1024];
        try (Base64OutputStream encoded = new Base64OutputStream(new OutputStream() {
            @Override public void write(int value) throws IOException { out.write(value); }
            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                out.write(new String(bytes, offset, length, StandardCharsets.US_ASCII));
            }
        }, Base64.NO_WRAP)) {
            int count;
            while ((count = file.read(buffer)) != -1) encoded.write(buffer, 0, count);
        } finally {
            Arrays.fill(buffer, (byte) 0);
        }
        out.write('"');
    }

    @Override public void write(int character) throws IOException {
        if (valueSyntax == null) out.write(character);
        else valueSyntax.append((char) character);
    }

    @Override public void write(char[] characters, int offset, int length) throws IOException {
        if (valueSyntax == null) out.write(characters, offset, length);
        else valueSyntax.append(characters, offset, length);
    }

    @Override public void write(String text, int offset, int length) throws IOException {
        if (valueSyntax == null) out.write(text, offset, length);
        else valueSyntax.append(text, offset, offset + length);
    }
}

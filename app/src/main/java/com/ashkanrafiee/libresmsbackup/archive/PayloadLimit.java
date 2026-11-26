package com.ashkanrafiee.libresmsbackup.archive;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Bounds payload bytes, not the container header. */
final class PayloadLimit {
    private static final long MAX_PLAINTEXT_BYTES = 2L * 1024 * 1024 * 1024;
    static final long MAX_ENCRYPTED_BYTES = 512L * 1024 * 1024;

    private PayloadLimit() {}

    static long bytes(boolean encrypted) {
        if (!encrypted) return MAX_PLAINTEXT_BYTES;
        // Android's AES/GCM provider can buffer the entire input and allocate a
        // separate output array. Leave room for both, buffer growth and the app.
        return Math.min(MAX_ENCRYPTED_BYTES, Runtime.getRuntime().maxMemory() / 4);
    }

    static InputStream read(InputStream source, long limit, boolean encrypted, boolean heapLimited) {
        return new InputStream() {
            private long remaining = limit;

            @Override public int read() throws IOException {
                int value = source.read();
                if (value != -1) consume(1);
                return value;
            }

            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                // Read at most one byte beyond the limit to distinguish EOF from overflow.
                int count = source.read(bytes, offset, (int) Math.min(length, remaining + 1));
                if (count > 0) consume(count);
                return count;
            }

            private void consume(int count) throws IOException {
                if (count > remaining) throw new ArchiveCrypto.PayloadTooLargeException(encrypted, heapLimited);
                remaining -= count;
            }

            @Override public void close() throws IOException { source.close(); }
        };
    }

    static OutputStream write(OutputStream destination, long limit, boolean encrypted) {
        return new FilterOutputStream(destination) {
            private long remaining = limit;

            @Override public void write(int value) throws IOException {
                consume(1);
                out.write(value);
            }

            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                consume(length);
                out.write(bytes, offset, length);
            }

            private void consume(int count) throws IOException {
                if (count > remaining) throw new ArchiveCrypto.PayloadTooLargeException(encrypted);
                remaining -= count;
            }
        };
    }
}

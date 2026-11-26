package com.ashkanrafiee.libresmsbackup.data;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

/**
 * Replaces attachment strings with byte ranges in the staged JSON.
 * The regular JSON parser still handles the surrounding structure and metadata.
 */
final class AttachmentJsonInput extends InputStream {
    private static final String ATTACHMENT = "dataBase64";
    private static final int MAX_DEPTH = 128;
    private static final int MAX_OBJECT_FIELDS = 1024;
    private static final int MAX_TOKEN_BYTES = 4 * 1024 * 1024;
    private static final int MAX_MMS_METADATA_BYTES = 8 * 1024 * 1024;
    private static final int MAX_MMS_CHILDREN = 4096;
    private enum Scope { DOCUMENT, ROOT, MMS, MESSAGES, MESSAGE, PARTS, PART, ADDRESSES, ADDRESS,
        RCS, FILES, FILE, OTHER }

    private final InputStream source;
    private final byte[] buffer = new byte[8192];
    private final ArrayDeque<Scope> scopes = new ArrayDeque<>();
    private final int[] fieldsAtDepth = new int[MAX_DEPTH];
    private final boolean[] mmsRowAtDepth = new boolean[MAX_DEPTH];
    private int position;
    private int size;
    private long offset;
    private boolean quoted;
    private boolean escaped;
    private int escapeDigits;
    private int tokenBytes;
    private int messageMetadataBytes;
    private int messageChildren;
    private boolean inMmsMessage;
    private boolean possibleName;
    private boolean pendingColon;
    private boolean pendingValue;
    private final StringBuilder name = new StringBuilder();
    private byte[] reference;
    private int referencePosition;

    AttachmentJsonInput(InputStream source) {
        this.source = source;
    }

    @Override public int read(byte[] bytes, int start, int length) throws IOException {
        checkBounds(bytes, start, length);
        int count = 0;
        while (count < length) {
            int value = read();
            if (value < 0) break;
            bytes[start + count++] = (byte) value;
        }
        return count == 0 && length != 0 ? -1 : count;
    }

    @Override public int read() throws IOException {
        int value = nextJsonByte();
        if (inMmsMessage && value != -1 && ++messageMetadataBytes > MAX_MMS_METADATA_BYTES)
            throw new IOException("MMS metadata is too large");
        return value;
    }

    private int nextJsonByte() throws IOException {
        if (reference != null) {
            int value = reference[referencePosition++] & 0xff;
            if (referencePosition == reference.length) reference = null;
            return value;
        }
        int value = nextByte();
        if (value < 0) return -1;
        if (quoted) {
            countTokenByte();
            if (escapeDigits > 0) {
                hexDigit(value);
                escapeDigits--;
            } else if (escaped) {
                escaped = false;
                if (value == 'u') escapeDigits = 4;
                else escapeCharacter(value);
            } else if (value == '\\') {
                escaped = true;
            } else if (value == '"') {
                quoted = false;
                pendingColon = possibleName;
                return value;
            } else if (value < 0x20) {
                throw new IOException("invalid JSON string");
            }
            if (possibleName) {
                if (name.length() < ATTACHMENT.length() * 6) name.append((char) value);
                else possibleName = false;
            }
            return value;
        }
        if (value == ' ' || value == '\t' || value == '\r' || value == '\n') {
            tokenBytes = 0;
            return value;
        }

        Scope scope = scopes.isEmpty() ? Scope.DOCUMENT : scopes.peek();
        if (pendingValue && (scope == Scope.PART || scope == Scope.FILE) && matchesName(ATTACHMENT)) {
            if (value != '"') throw new IOException("attachment data must be a JSON string");
            pendingValue = false;
            long start = offset;
            ByteReader bytes = this::nextByte;
            while (true) {
                int next = nextByte();
                if (next == '"') break;
                if (next < 0x20) throw new IOException("invalid MMS attachment string");
                if (next == '\\') readEscape(bytes);
            }
            reference = (start + ":" + (offset - start - 1) + "\"").getBytes(StandardCharsets.US_ASCII);
            referencePosition = 0;
            return value;
        }

        if (value == ':') {
            int depth = scopes.size() - 1;
            if (depth >= 0 && ++fieldsAtDepth[depth] > MAX_OBJECT_FIELDS)
                throw new IOException("backup object has too many fields");
            if (pendingColon && (scope == Scope.MESSAGE || scope == Scope.PART) && matchesName("row")) {
                if (mmsRowAtDepth[depth]) throw new IOException("duplicate MMS row");
                mmsRowAtDepth[depth] = true;
            }
            tokenBytes = 0;
            pendingValue = pendingColon;
        } else {
            if (value == '{' || value == '[') {
                tokenBytes = 0;
                if (scopes.size() >= MAX_DEPTH) throw new IOException("backup payload is nested too deeply");
                fieldsAtDepth[scopes.size()] = 0;
                mmsRowAtDepth[scopes.size()] = false;
                Scope child = childScope(scope, value);
                scopes.push(child);
                if (child == Scope.MESSAGE) {
                    inMmsMessage = true;
                    messageMetadataBytes = 0;
                    messageChildren = 0;
                }
                if ((child == Scope.PART || child == Scope.ADDRESS) && ++messageChildren > MAX_MMS_CHILDREN)
                    throw new IOException("MMS has too many parts or addresses");
            } else if ((value == '}' || value == ']') && !scopes.isEmpty()) {
                tokenBytes = 0;
                if (scopes.pop() == Scope.MESSAGE) inMmsMessage = false;
            } else if (value == '"') {
                tokenBytes = 0;
                quoted = true;
                possibleName = scope != Scope.OTHER && !pendingValue;
                name.setLength(0);
            } else if (value == ',') {
                tokenBytes = 0;
            } else {
                countTokenByte();
            }
            pendingValue = false;
        }
        pendingColon = false;
        return value;
    }

    private void countTokenByte() throws IOException {
        if (++tokenBytes > MAX_TOKEN_BYTES) throw new IOException("backup metadata value is too large");
    }

    private Scope childScope(Scope parent, int opener) throws IOException {
        if (opener == '{') {
            if (parent == Scope.DOCUMENT) return Scope.ROOT;
            if (parent == Scope.MESSAGES) return Scope.MESSAGE;
            if (parent == Scope.PARTS) return Scope.PART;
            if (parent == Scope.ADDRESSES) return Scope.ADDRESS;
            if (parent == Scope.FILES) return Scope.FILE;
            if (parent == Scope.ROOT && pendingValue) {
                if (matchesName("mms")) return Scope.MMS;
                if (matchesName("rcs")) return Scope.RCS;
            }
        } else if (pendingValue) {
            if (parent == Scope.MMS && matchesName("messages")) return Scope.MESSAGES;
            if (parent == Scope.MESSAGE) {
                if (matchesName("parts")) return Scope.PARTS;
                if (matchesName("addresses")) return Scope.ADDRESSES;
            }
            if (parent == Scope.RCS && matchesName("files")) return Scope.FILES;
        }
        return Scope.OTHER;
    }

    private boolean matchesName(String expected) throws IOException {
        int index = 0;
        for (int i = 0; i < name.length(); i++) {
            int value = name.charAt(i);
            if (value == '\\') {
                int[] position = {i + 1};
                value = readEscape(() -> position[0] < name.length() ? name.charAt(position[0]++) : -1);
                i = position[0] - 1;
            }
            if (index == expected.length() || expected.charAt(index++) != value) return false;
        }
        return index == expected.length();
    }

    private int nextByte() throws IOException {
        while (position == size) {
            int count = source.read(buffer);
            if (count < 0) return -1;
            position = 0;
            size = count;
        }
        offset++;
        return buffer[position++] & 0xff;
    }

    @Override public void close() throws IOException { source.close(); }

    private static void checkBounds(byte[] bytes, int offset, int length) {
        if (offset < 0 || length < 0 || offset > bytes.length - length) throw new IndexOutOfBoundsException();
    }

    private interface ByteReader { int read() throws IOException; }

    private static int readEscape(ByteReader reader) throws IOException {
        int escape = reader.read();
        if (escape != 'u') return escapeCharacter(escape);
        int code = 0;
        for (int i = 0; i < 4; i++) code = (code << 4) | hexDigit(reader.read());
        return code;
    }

    private static int escapeCharacter(int escape) throws IOException {
        switch (escape) {
            case '"': return '"';
            case '\\': return '\\';
            case '/': return '/';
            case 'b': return '\b';
            case 'f': return '\f';
            case 'n': return '\n';
            case 'r': return '\r';
            case 't': return '\t';
            default: throw new IOException("invalid JSON escape");
        }
    }

    private static int hexDigit(int digit) throws IOException {
        if (digit >= '0' && digit <= '9') return digit - '0';
        if (digit >= 'A' && digit <= 'F') return digit - 'A' + 10;
        if (digit >= 'a' && digit <= 'f') return digit - 'a' + 10;
        throw new IOException("invalid JSON escape");
    }

    static InputStream openRange(FileChannel channel, long offset, long length) {
        return new InputStream() {
            private final ByteBuffer bytes = ByteBuffer.allocate(8192);
            private long position = offset;
            private long remaining = length;
            {
                bytes.limit(0);
            }

            private boolean fill() throws IOException {
                if (!bytes.hasRemaining()) {
                    if (remaining == 0) return false;
                    bytes.clear();
                    bytes.limit((int) Math.min(bytes.capacity(), remaining));
                    int count;
                    do { count = channel.read(bytes, position); } while (count == 0);
                    if (count < 0) throw new EOFException("incomplete staged attachment");
                    position += count;
                    remaining -= count;
                    bytes.flip();
                }
                return true;
            }

            @Override public int read() throws IOException { return fill() ? bytes.get() & 0xff : -1; }

            @Override public int read(byte[] output, int offset, int length) throws IOException {
                checkBounds(output, offset, length);
                if (length == 0) return 0;
                if (!fill()) return -1;
                int count = Math.min(length, bytes.remaining());
                bytes.get(output, offset, count);
                return count;
            }
        };
    }

    static InputStream openBase64(FileChannel channel, long offset, long length) {
        InputStream raw = openRange(channel, offset, length);
        ByteReader reader = raw::read;
        return new InputStream() {
            @Override public int read() throws IOException {
                while (true) {
                    int value = raw.read();
                    if (value == '\\') value = readEscape(reader);
                    if (value < 0 || value == '=' || value == '+' || value == '/'
                            || (value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z')
                            || (value >= '0' && value <= '9')) return value;
                    // Match the legacy Android decoder, which skips non-Base64 bytes.
                }
            }
        };
    }
}

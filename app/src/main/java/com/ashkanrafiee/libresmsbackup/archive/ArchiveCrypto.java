package com.ashkanrafiee.libresmsbackup.archive;

import android.content.Context;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.AEADBadTagException;
import javax.crypto.BadPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** The small, versioned, password-encrypted container used by Libre SMS Backup. */
public final class ArchiveCrypto {
    static final byte[] MAGIC = "LSMBK002".getBytes(StandardCharsets.US_ASCII);
    static final int FORMAT_VERSION = 2;
    // Higher desktop-oriented work factors make an empty backup take many
    // seconds on common Android hardware. Keep this mobile-tuned value in
    // every archive header so it can be reviewed in a future format revision.
    static final int PBKDF2_ITERATIONS = 100_000;
    private static final int MAX_ITERATIONS = 6_000_000;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int MAX_HEADER_BYTES = 64 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ArchiveCrypto() {}

    public static boolean isAuthenticationFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof AEADBadTagException || current instanceof BadPaddingException) return true;
            current = current.getCause();
        }
        return false;
    }

    /** Writes the authenticated archive header and returns the stream for encrypted payload bytes. */
    public static OutputStream openEncrypted(OutputStream destination, Context context,
            char[] password) throws IOException {
        return openOutput(destination, context, password, true);
    }

    /** Writes a self-describing archive; encryption can be disabled only by an explicit user choice. */
    public static OutputStream openOutput(OutputStream destination, Context context,
            char[] password, boolean encrypted) throws IOException {
        if (destination == null || (encrypted && password == null)) throw new IllegalArgumentException();
        byte[] salt = new byte[SALT_BYTES];
        byte[] iv = new byte[IV_BYTES];
        byte[] header = null;
        byte[] keyBytes = null;
        try {
            JSONObject json = new JSONObject()
                    .put("format", "LSMBK")
                    .put("version", FORMAT_VERSION)
                    .put("createdAt", System.currentTimeMillis())
                    .put("appVersion", appVersion(context))
                    .put("appVersionCode", appVersionCode(context));
            if (encrypted) {
                RANDOM.nextBytes(salt);
                RANDOM.nextBytes(iv);
                json.put("encryption", "password")
                        .put("kdf", new JSONObject()
                                .put("algorithm", "PBKDF2WithHmacSHA256")
                                .put("iterations", PBKDF2_ITERATIONS)
                                .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                                .put("keyBits", KEY_BITS))
                        .put("cipher", new JSONObject()
                                .put("algorithm", "AES/GCM/NoPadding")
                                .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
                                .put("tagBits", TAG_BITS));
            } else {
                json.put("encryption", "none");
            }
            header = json.toString().getBytes(StandardCharsets.UTF_8);
            if (header.length > MAX_HEADER_BYTES) throw new IOException("archive header too large");
            destination.write(MAGIC);
            destination.write(FORMAT_VERSION);
            writeInt(destination, header.length);
            destination.write(header);
            if (!encrypted) return PayloadLimit.write(destination, PayloadLimit.bytes(false), false);
            keyBytes = deriveKey(password, salt, PBKDF2_ITERATIONS, KEY_BITS);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(header);

            return PayloadLimit.write(encryptingStream(destination, cipher), PayloadLimit.bytes(true), true);
        } catch (GeneralSecurityException | org.json.JSONException | RuntimeException e) {
            throw new IOException("Unable to initialize archive encryption", e);
        } finally {
            Arrays.fill(salt, (byte) 0);
            Arrays.fill(iv, (byte) 0);
            if (keyBytes != null) Arrays.fill(keyBytes, (byte) 0);
            if (header != null) Arrays.fill(header, (byte) 0);
        }
    }

    /** Opens the payload. Encrypted content is authenticated only after reading it to EOF. */
    public static InputStream openDecrypted(InputStream source, char[] password) throws IOException {
        return openPayload(source, password).stream;
    }

    static OpenedPayload openPayload(InputStream source, char[] password) throws IOException {
        if (source == null || password == null) throw new IllegalArgumentException();
        DataInputStream input = new DataInputStream(source);
        byte[] header = readHeader(input);
        try {
            JSONObject json = parseHeader(header);
            if (!isEncrypted(json))
                return new OpenedPayload(PayloadLimit.read(input, PayloadLimit.bytes(false), false), false);
            JSONObject kdf = json.getJSONObject("kdf");
            JSONObject cipherJson = json.getJSONObject("cipher");
            if (!"PBKDF2WithHmacSHA256".equals(kdf.getString("algorithm"))
                    || !"AES/GCM/NoPadding".equals(cipherJson.getString("algorithm"))
                    || kdf.getInt("keyBits") != KEY_BITS
                    || cipherJson.getInt("tagBits") != TAG_BITS) {
                throw new IOException("unsupported archive encryption");
            }
            int iterations = kdf.getInt("iterations");
            if (iterations < 1 || iterations > MAX_ITERATIONS)
                throw new IOException("unsupported archive work factor");
            byte[] salt = Base64.decode(kdf.getString("salt"), Base64.DEFAULT);
            byte[] iv = Base64.decode(cipherJson.getString("iv"), Base64.DEFAULT);
            if (salt.length < 8 || salt.length > 256 || iv.length != IV_BYTES)
                throw new IOException("invalid archive encryption parameters");
            byte[] keyBytes = deriveKey(password, salt, iterations, KEY_BITS);
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"),
                        new GCMParameterSpec(TAG_BITS, iv));
                cipher.updateAAD(header);
                InputStream bounded = PayloadLimit.read(input, PayloadLimit.bytes(true) + TAG_BITS / 8, true);
                return new OpenedPayload(decryptingStream(bounded, cipher), true);
            } finally {
                Arrays.fill(salt, (byte) 0);
                Arrays.fill(iv, (byte) 0);
                Arrays.fill(keyBytes, (byte) 0);
            }
        } catch (GeneralSecurityException | org.json.JSONException | RuntimeException e) {
            throw new IOException("Unable to open archive", e);
        } finally {
            Arrays.fill(header, (byte) 0);
        }
    }

    // Finalization and the final write must both succeed before a backup is published.
    private static OutputStream encryptingStream(OutputStream destination, Cipher cipher) {
        return new OutputStream() {
            private boolean closed;

            @Override public void write(int value) throws IOException {
                write(new byte[]{(byte) value}, 0, 1);
            }

            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                if (closed) throw new IOException("encrypted output is closed");
                byte[] encrypted = cipher.update(bytes, offset, length);
                if (encrypted != null) destination.write(encrypted);
            }

            @Override public void flush() throws IOException { destination.flush(); }

            @Override public void close() throws IOException {
                if (closed) return;
                closed = true;
                try (OutputStream output = destination) {
                    output.write(cipher.doFinal());
                } catch (GeneralSecurityException error) {
                    throw new IOException("could not finish archive encryption", error);
                }
            }
        };
    }

    // Android's CipherInputStream can allocate a larger output array on each
    // input chunk while GCM is still buffering. Use update's actual output instead.
    private static InputStream decryptingStream(InputStream source, Cipher cipher) {
        return new InputStream() {
            private final byte[] input = new byte[32 * 1024];
            private byte[] output;
            private int position;
            private boolean finished;

            @Override public int read() throws IOException {
                return fill() ? output[position++] & 0xff : -1;
            }

            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                if (length == 0) return 0;
                if (!fill()) return -1;
                int count = Math.min(length, output.length - position);
                System.arraycopy(output, position, bytes, offset, count);
                position += count;
                return count;
            }

            private boolean fill() throws IOException {
                while (output == null || position == output.length) {
                    clearOutput();
                    if (finished) return false;
                    int count = source.read(input);
                    if (count == -1) {
                        finished = true;
                        try {
                            output = cipher.doFinal();
                        } catch (GeneralSecurityException error) {
                            throw new IOException("could not authenticate archive", error);
                        }
                    } else if (count > 0) {
                        output = cipher.update(input, 0, count);
                    }
                }
                return true;
            }

            private void clearOutput() {
                if (output != null) Arrays.fill(output, (byte) 0);
                output = null;
                position = 0;
            }

            @Override public void close() throws IOException {
                finished = true;
                clearOutput();
                source.close();
            }
        };
    }

    /** Reads only the bounded container header so the UI can avoid asking for a password on plaintext archives. */
    public static boolean requiresPassword(InputStream source) throws IOException {
        if (source == null) throw new IllegalArgumentException();
        byte[] header = readHeader(new DataInputStream(source));
        try {
            return isEncrypted(parseHeader(header));
        } catch (org.json.JSONException | RuntimeException e) {
            throw new IOException("invalid archive header", e);
        } finally {
            Arrays.fill(header, (byte) 0);
        }
    }

    private static byte[] readHeader(DataInputStream input) throws IOException {
        byte[] magic = new byte[MAGIC.length];
        input.readFully(magic);
        if (!Arrays.equals(MAGIC, magic)) throw new IOException("unsupported archive");
        if (input.readUnsignedByte() != FORMAT_VERSION) throw new IOException("unsupported archive version");
        int length = input.readInt();
        if (length < 2 || length > MAX_HEADER_BYTES) throw new IOException("invalid archive header");
        byte[] header = new byte[length];
        input.readFully(header);
        return header;
    }

    private static JSONObject parseHeader(byte[] header) throws IOException, org.json.JSONException {
        JSONObject json = new JSONObject(new String(header, StandardCharsets.UTF_8));
        if (!"LSMBK".equals(json.optString("format")) || json.optInt("version", -1) != FORMAT_VERSION)
            throw new IOException("unsupported archive format");
        return json;
    }

    private static boolean isEncrypted(JSONObject header) throws IOException {
        String encryption = header.optString("encryption", "password");
        if ("password".equals(encryption)) return true;
        if ("none".equals(encryption)) return false;
        throw new IOException("unsupported archive encryption");
    }

    static final class OpenedPayload {
        final InputStream stream;
        final boolean encrypted;

        OpenedPayload(InputStream stream, boolean encrypted) {
            this.stream = stream;
            this.encrypted = encrypted;
        }
    }

    public static final class PayloadTooLargeException extends IOException {
        public final boolean encrypted;

        PayloadTooLargeException(boolean encrypted) {
            super("archive payload exceeds the size limit");
            this.encrypted = encrypted;
        }
    }

    // Kept package-visible for fast, dependency-free format tests.
    static byte[] encryptForTest(byte[] plaintext, char[] password) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        // Test archives do not need application metadata; this path only exercises the format.
        try {
            byte[] salt = new byte[SALT_BYTES];
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(salt);
            RANDOM.nextBytes(iv);
            byte[] header = ("{\"format\":\"LSMBK\",\"version\":" + FORMAT_VERSION + ",\"kdf\":{" +
                    "\"algorithm\":\"PBKDF2WithHmacSHA256\",\"iterations\":" +
                    PBKDF2_ITERATIONS + ",\"salt\":\"" + Base64.encodeToString(salt, Base64.NO_WRAP) +
                    "\",\"keyBits\":256},\"cipher\":{" +
                    "\"algorithm\":\"AES/GCM/NoPadding\",\"iv\":\"" +
                    Base64.encodeToString(iv, Base64.NO_WRAP) + "\",\"tagBits\":128}}")
                    .getBytes(StandardCharsets.UTF_8);
            byte[] key = deriveKey(password, salt, PBKDF2_ITERATIONS, KEY_BITS);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(header);
            output.write(MAGIC); output.write(FORMAT_VERSION); writeInt(output, header.length); output.write(header);
            CipherOutputStream encrypted = new CipherOutputStream(output, cipher);
            encrypted.write(plaintext); encrypted.close();
            Arrays.fill(key, (byte) 0); Arrays.fill(salt, (byte) 0); Arrays.fill(iv, (byte) 0);
            return output.toByteArray();
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    static byte[] decryptForTest(byte[] archive, char[] password) throws IOException {
        InputStream encrypted = openDecrypted(new ByteArrayInputStream(archive), password);
        ByteArrayOutputStream plaintext = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = encrypted.read(buffer)) != -1) plaintext.write(buffer, 0, count);
        encrypted.close();
        return plaintext.toByteArray();
    }

    private static byte[] deriveKey(char[] password, byte[] salt, int iterations, int bits)
            throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, bits);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            SecretKey key = factory.generateSecret(spec);
            return key.getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private static void writeInt(OutputStream output, int value) throws IOException {
        output.write((value >>> 24) & 0xff); output.write((value >>> 16) & 0xff);
        output.write((value >>> 8) & 0xff); output.write(value & 0xff);
    }

    private static String appVersion(Context context) {
        if (context == null) return "unknown";
        try { return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; }
        catch (Exception ignored) { return "unknown"; }
    }

    private static int appVersionCode(Context context) {
        if (context == null) return 0;
        try {
            android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.applicationInfo != null ? (int) (android.os.Build.VERSION.SDK_INT >= 28
                    ? info.getLongVersionCode() : info.versionCode) : 0;
        } catch (Exception ignored) { return 0; }
    }
}

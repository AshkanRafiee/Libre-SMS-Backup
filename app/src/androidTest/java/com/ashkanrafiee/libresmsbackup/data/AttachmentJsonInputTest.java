package com.ashkanrafiee.libresmsbackup.data;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;

import junit.framework.TestCase;

public class AttachmentJsonInputTest extends TestCase {
    public void testRestoresEscapedAttachmentByByteRangeWithoutChangingOtherFields() throws Exception {
        String prefix = "{\"sms\":[{\"body\":\"متن \\\"dataBase64\\\": \\\"x\\\"\","
                + "\"dataBase64\":\"keep\"}],\"\\u006dms\":{\"messages\":[{\"parts\":[{"
                + "\"row\":{\"dataBase64\":\"also keep\"},\"dataBas\\u006564\" : \"";
        String attachment = "  \\u002f\\/+\\/\\n";
        String between = "\"},{\"dataBase64\":\"";
        String suffix = "\"}]}]},\"rcs\":{\"files\":[{\"dataBase64\":\"YQ==\"}]}}";
        byte[] source = (prefix + attachment + between + suffix).getBytes(StandardCharsets.UTF_8);
        long start = prefix.getBytes(StandardCharsets.UTF_8).length;
        long emptyStart = start + attachment.length() + between.length();
        long rcsStart = source.length - "\"}]}}".length() - "YQ==".length();
        String expected = prefix + start + ":" + attachment.length() + between + emptyStart + ":0"
                + suffix.replace("YQ==", rcsStart + ":4");
        assertEquals(expected, new String(transform(source), StandardCharsets.UTF_8));

        File file = File.createTempFile("libresms-attachment-test-", ".json");
        try {
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(source);
            }
            try (FileInputStream input = new FileInputStream(file)) {
                FileChannel channel = input.getChannel();
                channel.position(17);
                try (InputStream data = java.util.Base64.getDecoder()
                        .wrap(AttachmentJsonInput.openBase64(channel, start, attachment.length()))) {
                    assertEquals(255, data.read());
                    assertEquals(255, data.read());
                    assertEquals(191, data.read());
                    assertEquals(-1, data.read());
                }
                assertEquals(17L, channel.position());
            }
        } finally {
            file.delete();
        }
    }

    public void testDoesNotHideInvalidAttachmentStringFromPreview() throws Exception {
        String prefix = "{\"mms\":{\"messages\":[{\"parts\":[{\"dataBase64\":\"";
        for (String invalid : new String[]{"\\u0X34\"}] }] }}", "\\u００３１", "abc", "\n\"}]}]}}"}) {
            try {
                transform((prefix + invalid).getBytes(StandardCharsets.UTF_8));
                fail("invalid attachment JSON must fail inspection");
            } catch (IOException expected) { }
        }
    }

    public void testRejectsNonStringAttachmentAndExcessiveStructure() throws Exception {
        StringBuilder excessiveFields = new StringBuilder("{");
        for (int field = 0; field < 1024; field++) excessiveFields.append("\"x\":0,");
        excessiveFields.append("\"last\":0}");
        StringBuilder deep = new StringBuilder("{\"unknown\":");
        for (int level = 0; level < 129; level++) deep.append('[');
        for (String invalid : new String[]{
                "{\"mms\":{\"messages\":[{\"parts\":[{\"dataBase64\":[0,1]}]}]}}",
                "{\"mms\":{\"messages\":[{\"row\":{},\"\\u0072ow\":{}}]}}",
                "{\"mms\":{\"messages\":[{\"parts\":[{\"row\":{},\"row\":{}}]}]}}",
                "{\"mms\":{\"messages\":[{\"addresses\":[{\"row\":{},\"row\":{}}]}]}}",
                deep.toString(), excessiveFields.toString()}) {
            try {
                transform(invalid.getBytes(StandardCharsets.UTF_8));
                fail("invalid JSON must not pass inspection");
            } catch (IOException expected) { }
        }
    }

    private static byte[] transform(byte[] source) throws IOException {
        InputStream shortReads = new ByteArrayInputStream(source) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                return super.read(bytes, offset, Math.min(length, 3));
            }
        };
        try (InputStream input = new AttachmentJsonInput(shortReads)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[5];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }
}

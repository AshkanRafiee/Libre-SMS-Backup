package com.ashkanrafiee.libresmsbackup.data;

import android.content.ContentResolver;
import android.content.ContextWrapper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import junit.framework.TestCase;

/** Verifies that restore previews count and validate records before import. */
public class RestoreEngineTest extends TestCase {
    public void testInspectCountsEachRestoreType() throws Exception {
        File archive = File.createTempFile("libresms-restore-test-", ".json");
        try {
            String json = "{\"schema\":2,\"sms\":[{\"body\":\"one\"},{\"body\":\"two\"}],"
                    + "\"calls\":[{\"number\":\"+1\"}]}";
            try (FileOutputStream output = new FileOutputStream(archive)) {
                output.write(json.getBytes(StandardCharsets.UTF_8));
            }
            RestoreEngine.ArchiveSummary summary = RestoreEngine.inspect(archive);
            assertEquals(2, summary.smsCount);
            assertEquals(1, summary.callsCount);
        } finally {
            archive.delete();
        }
    }

    public void testInspectCountsMmsAndRcsSections() throws Exception {
        File archive = File.createTempFile("libresms-restore-test-", ".json");
        try {
            String json = "{\"schema\":2,\"sms\":[],\"calls\":[],"
                    + "\"mms\":{\"status\":\"available\",\"messages\":[{\"ref\":\"1\",\"parts\":[],\"addresses\":[]}]},"
                    + "\"rcs\":{\"status\":\"available\",\"chats\":[{\"row\":{\"body\":\"hello\"}}]}}";
            try (FileOutputStream output = new FileOutputStream(archive)) {
                output.write(json.getBytes(StandardCharsets.UTF_8));
            }
            RestoreEngine.ArchiveSummary summary = RestoreEngine.inspect(archive);
            assertEquals(1, summary.mmsCount);
            assertEquals(1, summary.rcsCount);
        } finally {
            archive.delete();
        }
    }

    public void testInspectTracksSmsAndCallDateBounds() throws Exception {
        File archive = File.createTempFile("libresms-restore-test-", ".json");
        try {
            String json = "{\"schema\":2,\"sms\":[{\"date\":30},{\"date\":10}],"
                    + "\"calls\":[{\"date\":70},{\"date\":40}]}";
            try (FileOutputStream output = new FileOutputStream(archive)) {
                output.write(json.getBytes(StandardCharsets.UTF_8));
            }
            RestoreEngine.ArchiveSummary summary = RestoreEngine.inspect(archive);
            assertEquals(10L, summary.smsMinDate);
            assertEquals(30L, summary.smsMaxDate);
            assertEquals(40L, summary.callsMinDate);
            assertEquals(70L, summary.callsMaxDate);
        } finally {
            archive.delete();
        }
    }

    public void testFuturePayloadSchemaIsRejected() throws Exception {
        File archive = File.createTempFile("libresms-restore-test-", ".json");
        try {
            try (FileOutputStream output = new FileOutputStream(archive)) {
                output.write("{\"schema\":999,\"sms\":[],\"calls\":[]}".getBytes(StandardCharsets.UTF_8));
            }
            try {
                RestoreEngine.inspect(archive);
                fail("future payload schema must be rejected");
            } catch (java.io.IOException expected) { }
        } finally {
            archive.delete();
        }
    }

    public void testTrailingJsonIsRejectedBeforeRestore() throws Exception {
        File archive = File.createTempFile("libresms-restore-test-", ".json");
        try {
            try (FileOutputStream output = new FileOutputStream(archive)) {
                output.write("{\"schema\":2,\"sms\":[],\"calls\":[]}{}".getBytes(StandardCharsets.UTF_8));
            }
            try {
                RestoreEngine.inspect(archive);
                fail("trailing data must be rejected during inspection");
            } catch (java.io.IOException expected) { }
        } finally {
            archive.delete();
        }
    }

    public void testMalformedLaterMmsRowPreventsProviderAccess() throws Exception {
        File archive = File.createTempFile("libresms-restore-test-", ".json");
        try {
            String json = "{\"schema\":2,\"sms\":[{\"body\":\"would be written first\",\"date\":1}],"
                    + "\"mms\":{\"messages\":[{\"row\":{\"date\":2}},{\"row\":null}]}}";
            try (FileOutputStream output = new FileOutputStream(archive)) {
                output.write(json.getBytes(StandardCharsets.UTF_8));
            }
            ContextWrapper context = new ContextWrapper(null) {
                @Override public ContentResolver getContentResolver() {
                    throw new AssertionError("archive must be validated before provider access");
                }
            };
            try {
                RestoreEngine.restore(context, archive);
                fail("explicit null MMS row must be rejected before import");
            } catch (IOException expected) { }
        } finally {
            archive.delete();
        }
    }

    public void testInspectRejectsMalformedRecordStructures() throws Exception {
        String[] malformedSections = {
                "\"sms\":[{\"body\":[]}]",
                "\"calls\":[{\"duration\":true}]",
                "\"sms\":[{\"body\":\"earlier\"}],\"sms\":[]",
                "\"rcs\":{\"chats\":[{\"row\":{}}],\"chats\":[]}",
                "\"mms\":{\"messages\":[{}],\"messages\":[]}",
                "\"calls\":[{\"vendor_blob\":{\"encoding\":\"base64\",\"data\":[1]}}]",
                "\"mms\":{\"messages\":[null]}",
                "\"mms\":{\"messages\":[{\"dateMs\":null}]}",
                "\"mms\":{\"messages\":[{\"parts\":[{\"row\":[] }]}]}",
                "\"mms\":{\"messages\":[{\"parts\":[{\"dataBase64\":null}]}]}",
                "\"mms\":{\"messages\":[{\"parts\":[{\"dataBase64\":\"a===\"}]}]}",
                "\"mms\":{\"messages\":[{\"addresses\":[0]}]}",
                "\"rcs\":{\"chats\":[{\"row\":null}]}",
                "\"rcs\":{\"files\":[false]}"
        };
        File archive = File.createTempFile("libresms-restore-test-", ".json");
        try {
            for (String section : malformedSections) {
                try (FileOutputStream output = new FileOutputStream(archive)) {
                    output.write(("{\"schema\":2," + section + "}").getBytes(StandardCharsets.UTF_8));
                }
                try {
                    RestoreEngine.inspect(archive);
                    fail("malformed record structure accepted: " + section);
                } catch (IOException expected) { }
            }
        } finally {
            archive.delete();
        }
    }

    public void testOnlySafeProviderColumnNamesAreAccepted() {
        assertTrue(RestoreEngine.isSafeColumnName("vendor_123"));
        assertTrue(RestoreEngine.isSafeColumnName("_id"));
        assertFalse(RestoreEngine.isSafeColumnName("7foo"));
        assertFalse(RestoreEngine.isSafeColumnName("two columns"));
        assertFalse(RestoreEngine.isSafeColumnName("column)"));
        assertFalse(RestoreEngine.isSafeColumnName(""));
    }

    public void testInspectAcceptsLegacyMetadataAndShuffledFields() throws Exception {
        File archive = File.createTempFile("libresms-restore-test-", ".json");
        try {
            String json = "{\"mms\":{\"messages\":[{\"parts\":[{\"dataBas\\u006564\":\"YQ\\n==\","
                    + "\"row\":{\"ct\":\"image/png\",\"dataBase64\":\"provider metadata\"}}],"
                    + "\"addresses\":[{\"row\":{\"type\":\"137\",\"_id\":5}}],\"dateMs\":\"4000\","
                    + "\"row\":{\"msg_box\":1,\"date\":4}}]},"
                    + "\"calls\":[{\"vendor_blob\":{\"data\":\"Yg==\",\"extra\":[true],\"encoding\":\"base64\"},"
                    + "\"vendor_number\":1.25,\"date\":\"10\",\"name\":null}],"
                    + "\"rcs\":{\"files\":[{\"dataBase64\":\"Yw==\",\"row\":{\"vendor_column\":\"file\"}}],"
                    + "\"chats\":[{\"row\":{\"text\":\"chat\",\"remote_uri\":\"+1\",\"timestamp\":\"25\"}}]},"
                    + "\"sms\":[{\"body\":\"message\",\"date\":\"20\",\"vendor_ignored\":{\"anything\":true}}],"
                    + "\"schema\":2}";
            try (FileOutputStream output = new FileOutputStream(archive)) {
                output.write(json.getBytes(StandardCharsets.UTF_8));
            }
            RestoreEngine.ArchiveSummary summary = RestoreEngine.inspect(archive);
            assertEquals(1, summary.smsCount);
            assertEquals(1, summary.callsCount);
            assertEquals(1, summary.mmsCount);
            assertEquals(1, summary.rcsCount);
            assertEquals(20L, summary.smsMinDate);
            assertEquals(10L, summary.callsMaxDate);
        } finally {
            archive.delete();
        }
    }
}

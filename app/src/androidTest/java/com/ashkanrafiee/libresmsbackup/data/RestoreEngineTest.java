package com.ashkanrafiee.libresmsbackup.data;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import junit.framework.TestCase;

/** Verifies that restore previews expose independent SMS and call-log choices. */
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
}

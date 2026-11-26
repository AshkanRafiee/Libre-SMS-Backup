package com.ashkanrafiee.libresmsbackup.data;

import android.content.ContentValues;

import java.security.MessageDigest;

import junit.framework.TestCase;

public class MmsIdentityTest extends TestCase {
    public void testUnorderedChildrenPreserveMultiplicity() {
        ContentValues firstAddress = address("+15550100", 137);
        ContentValues secondAddress = address("+15550101", 151);
        ContentValues firstPart = textPart("First");
        ContentValues secondPart = textPart("Second");

        MmsIdentity original = new MmsIdentity();
        original.addAddress(firstAddress);
        original.addAddress(secondAddress);
        original.addAddress(secondAddress);
        original.addPart(firstPart, null);
        original.addPart(secondPart, null);
        original.addPart(firstPart, null);

        MmsIdentity reordered = new MmsIdentity();
        reordered.addPart(secondPart, null);
        reordered.addAddress(secondAddress);
        reordered.addPart(firstPart, null);
        reordered.addAddress(firstAddress);
        reordered.addAddress(secondAddress);
        reordered.addPart(firstPart, null);
        assertEquals(original.finish(), reordered.finish());

        MmsIdentity fewerAddresses = new MmsIdentity();
        fewerAddresses.addAddress(firstAddress);
        fewerAddresses.addAddress(secondAddress);
        fewerAddresses.addPart(firstPart, null);
        fewerAddresses.addPart(secondPart, null);
        fewerAddresses.addPart(firstPart, null);
        assertFalse(original.finish().equals(fewerAddresses.finish()));

        MmsIdentity fewerParts = new MmsIdentity();
        fewerParts.addAddress(firstAddress);
        fewerParts.addAddress(secondAddress);
        fewerParts.addAddress(secondAddress);
        fewerParts.addPart(firstPart, null);
        fewerParts.addPart(secondPart, null);
        assertFalse(original.finish().equals(fewerParts.finish()));
    }

    public void testProviderDefaultsAndPortableMetadata() {
        ContentValues archive = textPart("Body");
        ContentValues provider = new ContentValues(archive);
        provider.put("_id", 42);
        provider.put("mid", 7);
        provider.put("seq", 0);
        provider.put("chset", 0);
        assertEquals(partIdentity(archive, null), partIdentity(provider, null));

        provider.put("chset", 106);
        assertFalse(partIdentity(archive, null).equals(partIdentity(provider, null)));
        assertFalse(partIdentity(textPart(null), null).equals(partIdentity(textPart(""), null)));
    }

    public void testMissingBinaryCannotMatchEmptyOrNonemptyBinary() {
        String empty = MmsIdentity.digestKey(MmsIdentity.newDigest());
        MessageDigest digest = MmsIdentity.newDigest();
        digest.update((byte) 1);
        String nonempty = MmsIdentity.digestKey(digest);
        ContentValues file = new ContentValues();
        file.put("ct", "application/octet-stream");
        assertNull(partIdentity(file, null));
        assertNotNull(partIdentity(file, empty));
        assertFalse(partIdentity(file, empty).equals(partIdentity(file, nonempty)));

        ContentValues inline = textPart("Body");
        assertFalse(partIdentity(inline, null).equals(partIdentity(inline, empty)));
    }

    private static String partIdentity(ContentValues part, String binaryDigest) {
        MmsIdentity identity = new MmsIdentity();
        identity.addPart(part, binaryDigest);
        return identity.finish();
    }

    private static ContentValues address(String address, int type) {
        ContentValues values = new ContentValues();
        values.put("address", address);
        values.put("type", type);
        return values;
    }

    private static ContentValues textPart(String text) {
        ContentValues values = new ContentValues();
        values.put("ct", "text/plain");
        values.put("text", text);
        return values;
    }
}

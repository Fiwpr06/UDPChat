package com.udpchat;

import com.udpchat.shared.model.Attachment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class AttachmentTest {

    @Test
    public void testTruncateFileNameMiddleWithUserExample() {
        String longFileName = "1786325328006_2055742647082175291_3250971512653570209_82360a48678d546b710592896f5e8231.jpg";
        String truncated = Attachment.truncateFileNameMiddle(longFileName, 24);

        assertNotNull(truncated);
        assertEquals(24, truncated.length(), "Truncated length should not exceed 24 characters");
        assertTrue(truncated.startsWith("17863253280"), "Should keep the prefix of the filename");
        assertTrue(truncated.contains("..."), "Should contain ellipsis in the middle");
        assertTrue(truncated.endsWith(".jpg"), "Should preserve the file extension at the end");
    }

    @Test
    public void testTruncateFileNameShortNameUnchanged() {
        String shortName = "document.pdf";
        String result = Attachment.truncateFileNameMiddle(shortName, 24);
        assertEquals("document.pdf", result, "Short filename should not be modified");
    }

    @Test
    public void testTruncateFileNameWithoutExtension() {
        String noExtName = "a_very_long_file_name_without_any_extension_test_string";
        String truncated = Attachment.truncateFileNameMiddle(noExtName, 24);

        assertNotNull(truncated);
        assertTrue(truncated.length() <= 24);
        assertTrue(truncated.contains("..."));
    }

    @Test
    public void testAttachmentInstanceMethods() {
        Attachment att = new Attachment(
                "att-1",
                "1786325328006_2055742647082175291_3250971512653570209_82360a48678d546b710592896f5e8231.jpg",
                2048576,
                "IMAGE",
                "alice",
                "2026-10-02 12:00:00"
        );

        String truncated = att.getTruncatedFilename();
        assertEquals(24, truncated.length());
        assertTrue(truncated.endsWith(".jpg"));
        assertEquals("2.0 MB", att.formatSize());
        assertTrue(att.isImage());
    }
}

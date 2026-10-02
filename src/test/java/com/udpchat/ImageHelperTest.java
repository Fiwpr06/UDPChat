package com.udpchat;

import com.udpchat.client.util.ImageHelper;
import javafx.application.Platform;
import javafx.scene.image.Image;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class ImageHelperTest {

    @BeforeAll
    public static void initJavaFX() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException ignored) {}
    }

    @Test
    public void testIsImageExtension() {
        assertTrue(ImageHelper.isImageExtension("photo.png"));
        assertTrue(ImageHelper.isImageExtension("avatar.jpg"));
        assertTrue(ImageHelper.isImageExtension("picture.jpeg"));
        assertTrue(ImageHelper.isImageExtension("test.jfif"));
        assertTrue(ImageHelper.isImageExtension("icon.ico"));
        assertTrue(ImageHelper.isImageExtension("cursor.cur"));
        assertTrue(ImageHelper.isImageExtension("anim.gif"));
        assertTrue(ImageHelper.isImageExtension("draw.bmp"));
        assertTrue(ImageHelper.isImageExtension("scan.tif"));
        assertTrue(ImageHelper.isImageExtension("scan.tiff"));
        assertTrue(ImageHelper.isImageExtension("web.webp"));

        assertFalse(ImageHelper.isImageExtension("document.pdf"));
        assertFalse(ImageHelper.isImageExtension("archive.zip"));
        assertFalse(ImageHelper.isImageExtension("notes.txt"));
        assertFalse(ImageHelper.isImageExtension("audio.mp3"));
    }

    @Test
    public void testFormatNames() {
        assertEquals("PNG", ImageHelper.getImageFormatName("sample.png"));
        assertEquals("JPEG", ImageHelper.getImageFormatName("sample.jpg"));
        assertEquals("JPEG", ImageHelper.getImageFormatName("sample.jpeg"));
        assertEquals("JPEG", ImageHelper.getImageFormatName("sample.jfif"));
        assertEquals("ICO", ImageHelper.getImageFormatName("sample.ico"));
        assertEquals("ICO", ImageHelper.getImageFormatName("sample.cur"));
        assertEquals("GIF", ImageHelper.getImageFormatName("sample.gif"));
        assertEquals("BMP", ImageHelper.getImageFormatName("sample.bmp"));
        assertEquals("TIFF", ImageHelper.getImageFormatName("sample.tif"));
    }

    @Test
    public void testLoadPngJpgGifBmp() throws Exception {
        File tempDir = new File("target/test-image-helper");
        tempDir.mkdirs();

        // 1. Tạo ảnh BufferedImage mẫu
        BufferedImage bimg = new BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = bimg.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, 100, 80);
        g.setColor(Color.YELLOW);
        g.drawString("UDP Chat", 20, 45);
        g.dispose();

        String[] formats = new String[] { "png", "jpg", "gif", "bmp" };

        for (String fmt : formats) {
            File imgFile = new File(tempDir, "test." + fmt);
            ImageIO.write(bimg, fmt, imgFile);
            assertTrue(imgFile.exists());

            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<Image> loaded = new AtomicReference<>();
            Platform.runLater(() -> {
                try {
                    Image fxImg = ImageHelper.loadImage(imgFile);
                    loaded.set(fxImg);
                } finally {
                    latch.countDown();
                }
            });
            latch.await();

            Image fxImg = loaded.get();
            assertNotNull(fxImg, "Image for format " + fmt + " should not be null");
            assertFalse(fxImg.isError(), "Image for format " + fmt + " should not have error");
            assertEquals(100, (int) fxImg.getWidth(), "Width mismatch for " + fmt);
            assertEquals(80, (int) fxImg.getHeight(), "Height mismatch for " + fmt);
        }
    }

    @Test
    public void testLoadIcoFile() throws Exception {
        File icoFile = new File("C:/Program Files/JetBrains/IntelliJ IDEA 2025.3.1.1/bin/idea.ico");
        if (icoFile.exists()) {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<Image> loaded = new AtomicReference<>();
            Platform.runLater(() -> {
                try {
                    Image fxImg = ImageHelper.loadImage(icoFile);
                    loaded.set(fxImg);
                } finally {
                    latch.countDown();
                }
            });
            latch.await();

            Image fxImg = loaded.get();
            assertNotNull(fxImg, "ICO Image should not be null");
            assertFalse(fxImg.isError(), "ICO Image should not have error");
            assertTrue(fxImg.getWidth() > 0, "ICO width should be positive");
            assertTrue(fxImg.getHeight() > 0, "ICO height should be positive");
        }
    }
}

package com.tap_to_translate.snap_translate.hymt2.ocr;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class BubbleOverlayMaskerTest {
    @Test
    public void maskMakesOutsidePixelsTransparentAndPreservesInsideOpacity() {
        int[] pixels = new int[6];
        for (int i = 0; i < pixels.length; i++) pixels[i] = 0xc8abcdef;
        byte[] mask = new byte[] {(byte) 0xff, 0x00, (byte) 0x80, (byte) 0xff};

        int changed = BubbleOverlayMasker.applyMask(
                pixels, 3, 2, 0, 0, 1, 0, mask, 2, 2);

        assertEquals(2, changed);
        assertEquals(0xc8abcdef, pixels[0]);
        assertEquals(0xc8abcdef, pixels[1]);
        assertEquals(0x00abcdef, pixels[2]);
        assertEquals(0xc8abcdef, pixels[3]);
        assertEquals(0x64abcdef, pixels[4]);
        assertEquals(0xc8abcdef, pixels[5]);
    }

    @Test
    public void maskCoordinatesRemainCorrectForOffsetBitmapCrop() {
        int[] pixels = new int[] {0xff112233, 0xff445566};
        byte[] mask = new byte[] {0x00, (byte) 0xff, (byte) 0xff,
                0x00, 0x00, (byte) 0xff};

        BubbleOverlayMasker.applyMask(
                pixels, 2, 1, 1, 0, 0, 0, mask, 3, 2);

        assertEquals(0xff112233, pixels[0]);
        assertEquals(0xff445566, pixels[1]);
    }
}

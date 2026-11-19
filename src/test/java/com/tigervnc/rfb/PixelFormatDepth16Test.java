package com.tigervnc.rfb;

import org.junit.jupiter.api.Test;

import java.awt.image.ColorModel;
import java.awt.image.DirectColorModel;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * #15: the colour model of a depth-16 true-colour format is built from the format's own
 * shifts and maxes, so its pixels decode to the colours the server encoded.
 */
class PixelFormatDepth16Test {

  private static final PixelFormat RGB565 = new PixelFormat(16, 16, false, true, 31, 63, 31, 11, 5, 0);
  private static final PixelFormat BGR565 = new PixelFormat(16, 16, false, true, 31, 63, 31, 0, 5, 11);

  @Test
  void rgb565MasksComeFromTheFormat() {
    DirectColorModel cm = (DirectColorModel) RGB565.getColorModel();
    assertEquals(0xF800, cm.getRedMask(), "red mask");
    assertEquals(0x07E0, cm.getGreenMask(), "green mask");
    assertEquals(0x001F, cm.getBlueMask(), "blue mask");
  }

  @Test
  void rgb565PixelsDecodeToTheColoursEncoded() {
    ColorModel cm = RGB565.getColorModel();
    // Each colour is exactly representable in 5-6-5, so it must come back unchanged.
    int[][] colours = {{0, 255, 0}, {0, 0, 255}, {255, 0, 0}, {49, 4, 0}, {222, 194, 123}};
    for (int[] c : colours) {
      int pixel = ((c[0] >> 3) << 11) | ((c[1] >> 2) << 5) | (c[2] >> 3);
      int[] back = {cm.getRed(pixel), cm.getGreen(pixel), cm.getBlue(pixel)};
      for (int i = 0; i < 3; i++) {
        assertEquals(c[i] >> (i == 1 ? 2 : 3), back[i] >> (i == 1 ? 2 : 3),
            String.format("(%d,%d,%d) as 0x%04x came back as (%d,%d,%d)",
                c[0], c[1], c[2], pixel, back[0], back[1], back[2]));
      }
    }
  }

  @Test
  void bgr565UsesItsOwnShifts() {
    DirectColorModel cm = (DirectColorModel) BGR565.getColorModel();
    assertEquals(0x001F, cm.getRedMask(), "red mask");
    assertEquals(0x07E0, cm.getGreenMask(), "green mask");
    assertEquals(0xF800, cm.getBlueMask(), "blue mask");
  }
}

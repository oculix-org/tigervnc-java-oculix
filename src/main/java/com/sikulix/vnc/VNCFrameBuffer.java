/*
 * Copyright (c) 2017 - sikulix.com - MIT license
 */
package com.sikulix.vnc;

import com.tigervnc.rfb.FullFramePixelBuffer;
import com.tigervnc.rfb.PixelFormat;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.WritableRaster;
import java.nio.ByteOrder;

/**
 * An off-screen frame buffer that can be used to capture screen contents.
 *
 * The decoder threads of TigerVNC write into it under the lock of {@link #getImage()};
 * readers take the same lock and get a copy, never the live raster.
 */
class VNCFrameBuffer extends FullFramePixelBuffer {

  /**
   * 32 bits per pixel, 24 bits of colour, 8 bits per channel, in the byte order of this machine.
   * Fixed on purpose: it needs no graphics environment and loses no colour.
   */
  static final PixelFormat PIXEL_FORMAT = new PixelFormat(
          32,
          24,
          ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN,
          true,
          255,
          255,
          255,
          16,
          8,
          0
  );

  /** Creates a black frame buffer of the given size in {@link #PIXEL_FORMAT}. */
  VNCFrameBuffer(int width, int height) {
    super(PIXEL_FORMAT, width, height,
            PIXEL_FORMAT.getColorModel().createCompatibleWritableRaster(width, height));
    ColorModel cm = PIXEL_FORMAT.getColorModel();
    image = new BufferedImage(cm, data, cm.isAlphaPremultiplied(), null);
  }

  /**
   * Copies a region of the frame buffer into a new image.
   * Parts of the region outside the buffer stay black.
   */
  BufferedImage getImage(int x, int y, int w, int h) {
    ColorModel cm = PIXEL_FORMAT.getColorModel();
    WritableRaster raster = cm.createCompatibleWritableRaster(w, h);
    BufferedImage copy = new BufferedImage(cm, raster, cm.isAlphaPremultiplied(), null);
    Graphics2D g2d = copy.createGraphics();
    try {
      synchronized (image) {
        g2d.drawImage(image, 0, 0, w, h, x, y, x + w, y + h, null);
      }
    } finally {
      g2d.dispose();
    }
    return copy;
  }
}

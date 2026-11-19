package com.tigervnc.vncviewer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * #12: the images the Swing viewer loads by name are packaged next to its classes. Without
 * them, VncViewer's static initialiser throws before the viewer can start.
 */
class VncViewerResourcesTest {

  @Test
  void theViewerImagesAreOnTheClasspath() throws ClassNotFoundException {
    // Look the class up without initialising it: its static initialiser is what loads the images.
    Class<?> viewer = Class.forName("com.tigervnc.vncviewer.VncViewer", false,
        VncViewerResourcesTest.class.getClassLoader());
    for (String name : new String[] {"tigervnc.ico", "tigervnc.png", "secure.png", "insecure.png"}) {
      assertNotNull(viewer.getResource(name), name + " is not packaged with com.tigervnc.vncviewer");
    }
  }
}

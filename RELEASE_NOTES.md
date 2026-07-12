<div align="center">

[![Fix](https://img.shields.io/badge/fix-ZRLE%203--byte%20CPIXEL-1f883d?style=flat-square)](.)
[![Empirical](https://img.shields.io/badge/empirically%20reproduced-100%25-6f42c1?style=flat-square)](.)
[![Compat](https://img.shields.io/badge/no%20regression-legacy%20servers-0969da?style=flat-square)](.)

</div>

## What this fixes

The ZRLE decoder unconditionally read `bpp / 8` bytes per CPIXEL, while the RFB spec permits servers to omit the padding byte when `bpp == 32 && depth <= 24 && all RGB shifts fit in the low 3 bytes (0..23) OR the high 3 bytes (8..31)`. Against those servers, reading 4 bytes per CPIXEL instead of 3 desynchronised the zlib stream and crashed the decoder on the first non-trivial rectangle :

```
Exception in thread "Thread-0" com.tigervnc.rdr.Exception: ZlibInStream: inflate failed
    at com.tigervnc.rfb.ZRLEDecoder.readRect(ZRLEDecoder.java:68)
```

The fix applies the strict RFB conditional logic — 3 bytes/CPIXEL when the conditions are met, 4 bytes otherwise.

## Affected servers

Confirmed to emit 3-byte CPIXELs when conditions apply :

- **TigerVNC C++** (`rfb::ZRLEEncoder`)
- **TightVNC 2.x** (`rfb-sconn::ZrleEncoder`)
- **libvncserver** — used by vino (GNOME), x11vnc, krfb (KDE), etc.

Not affected (always emit 4 bytes/CPIXEL even at `bpp == 32`) — no regression :

- Legacy Xvnc (SUSE Linux Enterprise 12 and similar)
- Older RealVNC OEM
- Embedded VNC on cash registers / industrial consoles

## Empirical reproduction

Test against a localhost TightVNC 2.x server through the actual OculiX code path (`VNCScreen` → `com.sikulix.vnc.VNCClient` → `CConnection` → `ZRLEDecoder`) :

- **Before fix** : `ZlibInStream: inflate failed` on first capture, 100% reproducible, corrupted frame delivered in background
- **After fix** : clean 1920×1080 capture in ~170 ms, no crash, perfect image (visual validation of the real desktop)

## Migration

Bump the dep in the consumer `pom.xml` :

```xml
<dependency>
  <groupId>io.github.oculix-org</groupId>
  <artifactId>tigervnc-java-oculix</artifactId>
  <version>2.0.2</version>
</dependency>
```

No API change. No behaviour change against legacy servers. Silent improvement against modern servers.

## Credits

RFB-spec analysis, isolated reproducer and fix logic by @pantelisama in [oculix-org/Oculix#443](https://github.com/oculix-org/Oculix/issues/443). Thanks for the rigorous read and the patient silence during the 24 hours I was wrongly doubting.

🦎

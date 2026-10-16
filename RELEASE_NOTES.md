<div align="center">

[![Upstream](https://img.shields.io/badge/upstream-TigerVNC%20java%2F%208525719c-1f883d?style=flat-square)](.)
[![Headless](https://img.shields.io/badge/client-headless%2C%20no%20dialogs-0969da?style=flat-square)](.)
[![Handshake](https://img.shields.io/badge/handshake-cancellable-1f883d?style=flat-square)](.)
[![API](https://img.shields.io/badge/OculiX%20API-unchanged-8250df?style=flat-square)](.)
[![SSH](https://img.shields.io/badge/deps-jsch%20classic%20→%20mwiede%20fork-1f883d?style=flat-square)](.)
[![CVE](https://img.shields.io/badge/CVE--2023--48795-mitigated-d1242f?style=flat-square)](.)
[![Fix](https://img.shields.io/badge/fix-ZRLE%203--byte%20CPIXEL-1f883d?style=flat-square)](.)
[![Compat](https://img.shields.io/badge/no%20regression-legacy%20servers-0969da?style=flat-square)](.)

</div>

2.0.4 is the first release on Maven Central since 2.0.2. 2.0.3 was released on GitHub only, so everything it brought is part of this release too: the sections below cover both.

## 🔄 The TigerVNC core, taken from upstream

The `com.tigervnc` package is resynchronised with TigerVNC's `java/` directory at commit `8525719c`. The headless client in `com.sikulix.vnc` is rewritten on the new API instead of working around it: a `FullFramePixelBuffer` that TigerVNC's decoders write into themselves, in one fixed format, 32 bits per pixel and 8 bits per channel, with no graphics environment and no colour lost. A resized remote desktop gets a fresh buffer, and the server's cursor shape is ignored: a client with no screen has no cursor to draw.

## 🛑 A stalled handshake can be cancelled

`VNCClient.connect()` used to loop until the session was ready. A server that went quiet in the middle of the handshake kept the thread forever, socket open. Interrupting the thread now abandons the handshake: the socket is closed, the decoder threads are stopped, and the exception is rethrown with any close error attached. A test proves it: a fake server withholds its SecurityResult, the thread is interrupted, and the test requires the thread to have ended and the server to have read end-of-file, before any cleanup.

## 🔐 Passwords and certificates, with nobody to ask

- **One password per connection**, set for one step of the security exchange and removed in a `finally`: a thread no longer carries the password of its previous connection.
- **TigerVNC's questions** (unknown or expired certificate, hostname mismatch, server key never seen) **are answered no**, and the full question goes to the log, with how to trust a TLS server properly (`CSecurityTLS.X509CA`). No Swing dialog ever pops up in a headless process.
- **A refused certificate says why**: the reason reaches the TLS engine as a `CertificateException` with its cause, instead of a silent internal error. CA and CRL files are accepted only if they are regular files.
- Ident security asks for a user name only: the password getter no longer throws a `NullPointerException` on the missing password buffer.

## SSH: migrated from JCraft JSch to the mwiede fork

The TigerVNC Java viewer's SSH tunnel support (`com.tigervnc.vncviewer.Tunnel`, `PasswdDialog`) previously depended on `com.jcraft:jsch:0.1.55`. Upstream JCraft has been dormant since 2018, and the classic release predates every modern SSH hardening effort.

This release moves to [`com.github.mwiede:jsch:0.2.25`](https://github.com/mwiede/jsch) — the maintained continuation of the same codebase, which preserves the `com.jcraft.jsch` Java package. **No source change was needed** in `Tunnel.java`, `PasswdDialog.java`, or in downstream `org.sikuli.vnc.SSHTunnel` — the fork is a drop-in replacement.

What it brings:

- **Terrapin ([CVE-2023-48795](https://terrapin-attack.com/)) mitigated** since 0.2.15 (strict-kex extension)
- **Modern KEX** — `curve25519-sha256`, `curve25519-sha256@libssh.org`
- **Modern MAC/cipher** — `chacha20-poly1305@openssh.com`, `aes*-gcm@openssh.com`
- **Modern host-key algorithms** — `ssh-ed25519`, `rsa-sha2-256`, `rsa-sha2-512` (compatible with OpenSSH 8.8+ where `ssh-rsa` is deprecated server-side)

Validated end-to-end against a WSL2 sshd (`ssh-rsa` disabled server-side) — connection succeeds with classic mwiede defaults, whereas the classic 0.1.55 hangs on KEX negotiation.

## ZRLE decoder: honour 3-byte CPIXEL compact form

The ZRLE decoder unconditionally read `bpp / 8` bytes per CPIXEL, while the RFB spec permits servers to omit the padding byte when `bpp == 32 && depth <= 24 && all RGB shifts fit in the low 3 bytes (0..23) OR the high 3 bytes (8..31)`. Against those servers, reading 4 bytes per CPIXEL instead of 3 desynchronised the zlib stream and crashed the decoder on the first non-trivial rectangle:

```
Exception in thread "Thread-0" com.tigervnc.rdr.Exception: ZlibInStream: inflate failed
    at com.tigervnc.rfb.ZRLEDecoder.readRect(ZRLEDecoder.java:68)
```

The fix applies the strict RFB conditional logic — 3 bytes/CPIXEL when the conditions are met, 4 bytes otherwise.

### Affected servers

Confirmed to emit 3-byte CPIXELs when conditions apply:

- **TigerVNC C++** (`rfb::ZRLEEncoder`)
- **TightVNC 2.x** (`rfb-sconn::ZrleEncoder`)
- **libvncserver** — used by vino (GNOME), x11vnc, krfb (KDE), etc.

Not affected (always emit 4 bytes/CPIXEL even at `bpp == 32`) — no regression:

- Legacy Xvnc (SUSE Linux Enterprise 12 and similar)
- Older RealVNC OEM
- Embedded VNC on cash registers / industrial consoles

### Empirical reproduction

Tested against a localhost TightVNC 2.x server through the actual OculiX code path (`VNCScreen` → `com.sikulix.vnc.VNCClient` → `CConnection` → `ZRLEDecoder`):

- **Before fix**: `ZlibInStream: inflate failed` on first capture, 100% reproducible, corrupted frame delivered in background
- **After fix**: clean 1920×1080 capture in ~170 ms, no crash, perfect image (visual validation of the real desktop)

## Migration

Bump the dep in the consumer `pom.xml`:

```xml
<dependency>
  <groupId>io.github.oculix-org</groupId>
  <artifactId>tigervnc-java-oculix</artifactId>
  <version>2.0.4</version>
</dependency>
```

The methods OculiX calls (`connect`, `processMessages`, `refreshFramebuffer`, `getFrameBuffer`, `getBounds`, `keyDown`, `keyUp`, `mouseEvent`, `close`) keep their signatures. `VNCClient`'s public drawing callbacks (`fillRect`, `imageRect`, `copyRect`, `serverInit`, `setDesktopSize`) are gone: TigerVNC draws now, and nothing outside had a reason to call them. No behaviour change against legacy servers or legacy SSH endpoints.

## Credits

- ZRLE 3-byte CPIXEL: RFB-spec analysis, isolated reproducer and fix logic by @pantelisama in [oculix-org/Oculix#443](https://github.com/oculix-org/Oculix/issues/443). Thanks for the rigorous read and the patient silence during the 24 hours I was wrongly doubting.
- jsch migration: motivated by the mwiede fork's sustained maintenance since 2019 — kudos to @mwiede for keeping the classic API alive and hardened.

🦎

# tigervnc-java-oculix

[![License: GPL v2+](https://img.shields.io/badge/License-GPL%20v2%2B-blue.svg)](./LICENSE)
[![Java](https://img.shields.io/badge/Java-11%2B-orange.svg)](https://adoptium.net/)
[![Maven Central](https://img.shields.io/badge/Maven%20Central-pending-lightgrey.svg)]()

A standalone, Maven-distributed, GPL v2 fork of the **TigerVNC Java viewer**,
maintained as an external dependency of the [Oculix](https://github.com/oculix-org/Oculix)
visual automation framework.

> **TL;DR** &nbsp; If you need to embed a VNC (RFB) client into a Java
> application and you are comfortable with the GNU General Public License
> version 2, this is a ready-to-use Maven artifact.

---

## Table of contents

- [What this is](#what-this-is)
- [Why this repository exists](#why-this-repository-exists)
- [License](#license)
- [Maven coordinates](#maven-coordinates)
- [Dependencies](#dependencies)
- [Package map](#package-map)
- [Build](#build)
- [Publishing to Maven Central](#publishing-to-maven-central)
- [Relationship with upstream TigerVNC](#relationship-with-upstream-tigervnc)
- [Modifications summary](#modifications-summary)
- [Project lineage](#project-lineage)
- [Versioning](#versioning)
- [Contributing](#contributing)
- [Credits](#credits)

---

## What this is

`tigervnc-java-oculix` is a ready-to-consume Maven artifact that provides the
**Java implementation of the RFB (Remote Framebuffer) protocol** used by VNC
servers and clients.  It contains:

- The full `com.tigervnc.*` tree (network, rdr, rfb, vncviewer) -- the Java
  viewer originally written and maintained by the [TigerVNC](https://tigervnc.org)
  project.
- A thin wrapper layer `com.sikulix.vnc.*` (VNCClient, VNCFrameBuffer,
  VNCClipboard, XKeySym, security helpers) originally authored by Raimund
  Hocke (sikulix.com) to expose a simpler, headless-friendly API for
  automation use cases.
- Minor bug fixes by the Oculix maintainers -- most notably CPIXEL mismatch
  fixes in the ZRLE and Tight decoders, and a corrected big-endian /
  little-endian pixel reader in `InStream`.

The end result is a 100 % pure-Java VNC client that can:

- Connect to any RFB v3.3 / v3.7 / v3.8 VNC server.
- Decode Raw, CopyRect, RRE, Hextile, ZRLE, and Tight encodings.
- Authenticate via VncAuth, None, Plain, Ident, VeNCrypt, and TLS.
- Open SSH tunnels to VNC hosts (via JCraft JSch).
- Render the framebuffer into a `java.awt.image.BufferedImage`, perfect for
  screenshot-based automation, testing, or image processing.

---

## Why this repository exists

The TigerVNC Java viewer is licensed under the **GNU General Public License,
version 2 or later**.  Any work that statically links or bundles those
sources must itself be distributed under GPL-compatible terms.

Oculix -- the parent project -- is licensed under the **MIT License**.
Between February 2026 and April 2026, the TigerVNC Java sources were
inadvertently vendored directly into the Oculix `API` module, which broke
the MIT guarantee: downstream consumers of `oculix-api` were pulling GPL
code without their knowledge.

To restore clean license boundaries, the `com.tigervnc.*` and
`com.sikulix.vnc.*` trees were extracted back out of Oculix into this
standalone repository.  Oculix now declares `tigervnc-java-oculix` as an
**optional Maven dependency**, so downstream consumers who want VNC support
explicitly opt into the GPL terms.

This follows exactly the same pattern that Raimund Hocke used for
[`com.sikulix:sikulix2tigervnc`](https://repo1.maven.org/maven2/com/sikulix/sikulix2tigervnc/1.1.4/)
between 2017 and 2019, which is the direct ancestor of this repository.
See [Project lineage](#project-lineage) for the full story.

---

## License

**GNU General Public License, version 2 (or, at your option, any later
version).**  The full text is in [`LICENSE`](./LICENSE) and the detailed
authorship and modification history is in [`NOTICE`](./NOTICE).

**Practical implications:**

- You MAY use this artifact in GPL-compatible projects.
- You MAY link this artifact dynamically (Maven dependency) from projects
  under *permissive* licenses (MIT, Apache-2.0, BSD).  The static-versus-
  dynamic distinction is legally nuanced; the conservative interpretation
  is that any distribution that bundles this artifact together with a
  permissive project triggers GPL obligations on the whole combined work.
- You MUST preserve the GPL v2 license notice in every file you
  redistribute.
- You MUST make the full source code available to anyone who receives a
  binary built from this artifact.

If any of the above is unclear for your use case, consult a lawyer or the
[FSF's licensing FAQ](https://www.gnu.org/licenses/gpl-faq.html).

---

## Maven coordinates

```xml
<dependency>
    <groupId>io.github.oculix-org</groupId>
    <artifactId>tigervnc-java-oculix</artifactId>
    <version>2.0.0</version>
</dependency>
```

For downstream projects that want to remain permissively licensed, declare
it as optional:

```xml
<dependency>
    <groupId>io.github.oculix-org</groupId>
    <artifactId>tigervnc-java-oculix</artifactId>
    <version>2.0.0</version>
    <optional>true</optional>
    <!-- License: GPL v2 or later -->
</dependency>
```

---

## Dependencies

This artifact pulls three external dependencies -- all are either
BSD-licensed (GPL-compatible) or the JDK's JAXB API that was removed from
the JDK in Java 11.

| GroupId           | ArtifactId | Version | License     | Used by                                  |
|-------------------|------------|---------|-------------|------------------------------------------|
| `com.jcraft`      | `jsch`     | 0.1.55  | BSD 3-cl.   | `vncviewer/Tunnel.java`, `PasswdDialog`  |
| `com.jcraft`      | `jzlib`    | 1.1.3   | BSD 3-cl.   | `rdr/ZlibInStream.java`                  |
| `javax.xml.bind`  | `jaxb-api` | 2.3.1   | CDDL-1.1    | `rfb/CSecurityTLS.java`                  |

Everything else (Swing, JNDI, JSSE, zip streams) is provided by the JDK.

> **Note on `jsch`:** The original SikuliX-vendored sources targeted JSch
> `0.1.53` (June 2015).  We pin to `0.1.55` (the last classic JCraft release)
> because it ships the same public API plus security fixes.  Consumers who
> require a modern, actively-maintained fork should override this dependency
> with `com.github.mwiede:jsch` in their own `<dependencyManagement>`; the
> tigervnc code uses only the classic public API, so the mwiede fork
> drop-in replacement works.

---

## Package map

```
com.tigervnc.network        Low-level network I/O
                              TcpSocket, SocketDescriptor, SSLEngineManager

com.tigervnc.rdr            RFB reader/writer streams
                              FdInStream, FdOutStream, ZlibInStream,
                              TLSInStream, TLSOutStream, MemInStream

com.tigervnc.rfb            RFB protocol implementation
                              CConnection, CMsgReader/Writer (v3),
                              ConnParams, PixelFormat, PixelBuffer,
                              ZRLEDecoder, TightDecoder, HextileDecoder,
                              RREDecoder, RawDecoder,
                              CSecurityNone / VncAuth / Plain / Ident /
                              VeNCrypt / TLS, Cursor, Keysyms, LogWriter

com.tigervnc.vncviewer      Optional Swing-based viewer application
                              VncViewer, CConn, DesktopWindow, Viewport,
                              OptionsDialog, ServerDialog, PasswdDialog,
                              ClipboardDialog, F8Menu, MenuKey, Tunnel

com.sikulix.vnc             Thin automation-friendly wrappers
                              VNCClient, VNCFrameBuffer, VNCClipboard,
                              XKeySym, BasicUserPasswdGetter,
                              ThreadLocalSecurityClient
```

The Swing viewer under `com.tigervnc.vncviewer` is retained for
completeness but is **not** required to use the headless
`com.sikulix.vnc.VNCClient` API.  A tree-shaker (Proguard, R8) can safely
strip `com.tigervnc.vncviewer.*` if the viewer is not needed.

---

## Build

Requirements:

- JDK 11 or later (tested on 11, 17, 21).
- Maven 3.6.3 or later.

```bash
git clone https://github.com/oculix-org/tigervnc-java-oculix.git
cd tigervnc-java-oculix
mvn clean install
```

To build the sources jar and javadoc jar alongside the main jar:

```bash
mvn clean install -P build-source,build-docs
```

To build signed artifacts (requires a GPG key on the path):

```bash
mvn clean install -P sign
```

---

## Publishing to Maven Central

The project uses the new Sonatype Central Portal publishing plugin
(`org.sonatype.central:central-publishing-maven-plugin`) together with
`maven-gpg-plugin`.  Both are activated by the `release` profile.

```bash
mvn clean deploy -P release
```

Prerequisites (one-time setup):

1. A verified Sonatype Central Portal account with the `io.github.oculix-org`
   namespace claimed.
2. A `~/.m2/settings.xml` server entry:

   ```xml
   <server>
     <id>central</id>
     <username>YOUR_CENTRAL_TOKEN_USERNAME</username>
     <password>YOUR_CENTRAL_TOKEN_PASSWORD</password>
   </server>
   ```

3. A GPG key pair published to `keys.openpgp.org` and available to
   `maven-gpg-plugin`.  Set `GPG_PASSPHRASE` in your shell or configure a
   GPG agent with loopback pinentry.

4. The `release` profile will automatically attach `-sources.jar`,
   `-javadoc.jar`, and `.asc` signatures for every artifact.

---

## Relationship with upstream TigerVNC

This repository is a **downstream fork**, not a replacement for upstream
TigerVNC.  The public TigerVNC project -- <https://github.com/TigerVNC/tigervnc> --
remains the authoritative source for the Java viewer.

Changes in this fork that are **generic bug fixes** (for example the CPIXEL
mismatch fix in ZRLEDecoder / TightDecoder / InStream) are good
candidates for submission as pull requests to upstream.  Doing so reduces
the maintenance burden of carrying these patches locally.

Changes that are **packaging-specific** (Maven layout, coordinate naming,
wrapper classes in `com.sikulix.vnc.*`) are intentionally kept in this
fork only.

If you discover a bug that also reproduces with vanilla upstream TigerVNC,
please report it upstream first:
<https://github.com/TigerVNC/tigervnc/issues>.

---

## Modifications summary

A complete per-line modifications history is preserved in the git log of
this repository.  The high-level summary:

| Date        | Scope                                                       | Author                          |
|-------------|-------------------------------------------------------------|---------------------------------|
| 2002--2005  | Original RFB protocol implementation                        | RealVNC Ltd.                    |
| 2011--2019  | Java viewer development, decoders, security                 | Brian P. Hinz & TigerVNC        |
| 2017--2019  | Maven repackaging as `com.sikulix:sikulix2tigervnc`         | Raimund Hocke                   |
| 2026-02-25  | Vendoring into Oculix `API` module                          | Oculix maintainers              |
| 2026-02-26  | CPIXEL mismatch fix (ZRLE / Tight / InStream)               | Oculix maintainers              |
| 2026-02-26  | Force disable CPIXEL optimisation                           | Oculix maintainers              |
| 2026-02-26  | Restrict security sub-types to VncAuth (EndOfStream fix)    | Oculix maintainers              |
| 2026-04-11  | Deprecation / type-warning cleanup (~10 lines)              | `julienmerconsulting`           |
| 2026-04-16  | Extraction into this standalone repository                  | Oculix maintainers              |

See [`NOTICE`](./NOTICE) for the formal GPL v2 section 2(a) notice.

---

## Project lineage

```
                  TigerVNC upstream (GPL v2)
                  github.com/TigerVNC/tigervnc
                            |
                            v
               sikulix2tigervnc (GPL v2, 2017-2019)
               github.com/RaiMan/sikulix2tigervnc   <-- deleted early 2026
                            |  maven: com.sikulix:sikulix2tigervnc:1.1.4
                            v
                  SikuliX1 API (MIT) -- used as dependency
                  github.com/RaiMan/SikuliX1           <-- archived Mar 2026
                            |  fork
                            v
                       Oculix API (MIT)
                  github.com/oculix-org/Oculix
                            |  sources inlined Feb 2026 (license break)
                            v
                            |  extracted Apr 2026 (license restored)
                            v
          +-- MIT clean core ---->  Oculix API (MIT, unchanged)
          |
          +-- GPL split off ----->  tigervnc-java-oculix (GPL v2)  <-- YOU ARE HERE
                                   github.com/oculix-org/tigervnc-java-oculix
                                   maven: io.github.oculix-org:tigervnc-java-oculix:2.0.0
```

---

## Versioning

We start at version `2.0.0`, continuing from the `2.0.0-SNAPSHOT` that
Raimund Hocke had begun on the now-deleted sikulix2tigervnc repository
(which, per the historical record on Maven Central, was the planned
successor to `sikulix2tigervnc:1.1.4`).

Semantic versioning applies from here on out:

- `MAJOR` -- incompatible API changes, upstream TigerVNC major bumps,
  JDK minimum-version changes.
- `MINOR` -- backwards-compatible new features, new encodings, new
  security sub-types.
- `PATCH` -- bug fixes that preserve the public API.

---

## Contributing

Issues and pull requests are welcome, with the following caveats:

- **Generic bugs** (affecting behaviour regardless of the Oculix use
  case) should ideally be filed upstream first at
  <https://github.com/TigerVNC/tigervnc/issues>.  If the upstream project
  is slow to react, we may accept the fix here first.
- **Packaging changes** (Maven POM, coordinates, CI) are accepted here.
- **License-sensitive changes** (new files, imported code) must preserve
  the GPL v2 licensing of the repository.  Contributors retain their
  copyright but agree to license their contribution under GPL v2 (or any
  later version) by opening a pull request.

---

## Credits

Without the work of the following people and organisations, this
repository would not exist:

- **Brian P. Hinz** and the [TigerVNC project](https://tigervnc.org/) --
  for the Java viewer that forms 99 % of this codebase.
- **RealVNC Ltd.** and the former **AT&T Laboratories Cambridge** RFB
  team -- for the original VNC protocol and reference implementation.
- **Raimund Hocke** (`RaiMan`, sikulix.com) -- for many years of
  packaging and maintaining `sikulix2tigervnc` and for authoring the
  `com.sikulix.vnc.*` wrapper layer that makes this code usable from
  automation frameworks.
- **JCraft, Inc.** (`ymnk`) -- for JSch and JZlib, which the TigerVNC
  Java viewer relies on for SSH tunnelling and zlib decompression.

Fork maintained by **Julien Mer** (`julienmerconsulting`) on behalf of
the Oculix project.

---

## Contact

- Repository : <https://github.com/oculix-org/tigervnc-java-oculix>
- Issues     : <https://github.com/oculix-org/tigervnc-java-oculix/issues>
- Maintainer : Julien Mer -- `julien.mer38 [at] gmail.com`
- Upstream   : <https://github.com/TigerVNC/tigervnc>

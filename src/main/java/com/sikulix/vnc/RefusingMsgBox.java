/*
 * Copyright (c) 2026 - oculix-org - MIT license
 */
package com.sikulix.vnc;

import com.tigervnc.rfb.UserMsgBox;

/**
 * Answers the questions TigerVNC would ask a user (unknown or expired certificate, unknown
 * server key) without any window: the question goes to the log in full, and the answer is no.
 *
 * Installed once in the static field {@code CSecurity.msg}, shared by every connection of the
 * JVM; it holds no state, so concurrent connections cannot interfere through it.
 */
class RefusingMsgBox implements UserMsgBox
{
    /** Logs the question with everything TigerVNC shows a user, then refuses. */
    @Override
    public boolean showMsgBox(int flags, String title, String text)
    {
        VNCClient.vlog.error("Refused without asking (no user to ask): " + title + "\n" + text
                + "\nTo trust a TLS server, declare its CA certificate in CSecurityTLS.X509CA.");
        return false;
    }
}

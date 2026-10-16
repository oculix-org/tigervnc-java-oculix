/*
 * Copyright (c) 2017 - sikulix.com - MIT license
 */
package com.sikulix.vnc;

import com.tigervnc.rfb.CConnection;
import com.tigervnc.rfb.CSecurity;
import com.tigervnc.rfb.SecurityClient;
import com.tigervnc.rfb.UserPasswdGetter;

/**
 * Workaround for static state in TigerVNC.
 *
 * The UserPasswdGetter used by TigerVNC is a static field (CSecurity.upg).
 * In SikuliX need to be able to specify a password per connection without requesting user input.
 * To do that we set the global UserPasswdGetter to one that retrieves the actual UserPasswdGetter
 * instance from a thread-local variable.
 * This extension of SecurityClient sets the thread-local variable to the correct value just before
 * it is going to be requested by the TigerVNC code, and clears it right after.
 */
class ThreadLocalSecurityClient extends SecurityClient
{
    private static final ThreadLocal<UserPasswdGetter> UPG = new ThreadLocal<>();
    static {
        CSecurity.upg = new UserPasswdGetter()
        {
            /** Delegates to the getter of the connection being authenticated on this thread. */
            @Override
            public void getUserPasswd(boolean secure, StringBuffer user, StringBuffer pass)
            {
                UserPasswdGetter upg = UPG.get();
                if (upg != null) {
                    upg.getUserPasswd(secure, user, pass);
                } else {
                    if (user != null) {
                        user.setLength(0);
                    }
                    if (pass != null) {
                        pass.setLength(0);
                    }
                }
            }
        };
    }

    private final UserPasswdGetter connectionGetter;

    /** Binds the security exchange of one connection to its own password getter. */
    ThreadLocalSecurityClient(UserPasswdGetter userPasswdGetter)
    {
        connectionGetter = userPasswdGetter;
    }

    /**
     * Wraps the security type chosen by TigerVNC so that each step of its exchange sees this
     * connection's password getter, and only for the duration of that step.
     */
    @Override
    public CSecurity GetCSecurity(int securityType)
    {
        final CSecurity security = super.GetCSecurity(securityType);
        if (security != null) {
            return new CSecurity()
            {
                /** Runs one step of the exchange with the getter installed on the calling thread. */
                @Override
                public boolean processMsg(CConnection cConnection)
                {
                    UPG.set(connectionGetter);
                    try {
                        return security.processMsg(cConnection);
                    } finally {
                        UPG.remove();
                    }
                }

                /** Reports the wrapped security type. */
                @Override
                public int getType()
                {
                    return security.getType();
                }

                /** Reports the wrapped description. */
                @Override
                public String description()
                {
                    return security.description();
                }

                /** Reports whether the wrapped security encrypts the channel. */
                @Override
                public boolean isSecure()
                {
                    return security.isSecure();
                }
            };
        } else {
            return null;
        }
    }
}

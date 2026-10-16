/*
 * Copyright (c) 2017 - sikulix.com - MIT license
 */
package com.sikulix.vnc;

import com.tigervnc.rfb.UserPasswdGetter;

/**
 * Simple implementation of UserPasswdGetter that returns a fixed password.
 */
class BasicUserPasswdGetter implements UserPasswdGetter
{
    private final String password;

    public BasicUserPasswdGetter(String password)
    {
        this.password = password;
    }

    /**
     * Hands TigerVNC the fixed password, whatever the secure flag says; without a password the
     * buffer is left empty. Ident security asks for a user name only and passes {@code null}
     * for the password buffer.
     */
    @Override
    public void getUserPasswd(boolean secure, StringBuffer user, StringBuffer passwd)
    {
        if (user != null) {
            user.setLength(0);
        }

        if (passwd != null) {
            passwd.setLength(0);
            if (password != null) {
                passwd.append(password);
            }
        }
    }
}

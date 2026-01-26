/*******************************************************************************
 * Copyright (c) 2021 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 * Red Hat, Inc. - initial API and implementation
 ******************************************************************************/
package ee.carlrobert.codegpt.telemetry.core.util;

import java.util.regex.Pattern;

public class AnonymizeUtils {

    public static final String USER_NAME = System.getProperty("user.name");
    public static final String ANONYMOUS_USER_NAME = "<USER>";
    public static final String HOME_DIR = System.getProperty("user.home");
    public static final String ANONYMOUS_HOMEDIR = "<HOMEDIR>";
    public static final String TMP_DIR = System.getProperty("java.io.tmpdir");
    public static final String ANONYMOUS_TMPDIR = "<TMPDIR>";
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,6}",
            Pattern.CASE_INSENSITIVE);
    public static final String ANONYMOUS_EMAIL = "<EMAIL>";
    private static final Pattern IP_PATTERN = Pattern.compile(
            "(([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\.){3}([01]?\\d\\d?|2[0-4]\\d|25[0-5])");
    public static final String ANONYMOUS_IP = "<IP>";
    public static final String ANONYMOUS_RESOURCENAME = "<RESOURCENAME>";
    public static final String ANONYMOUS_NAMESPACE = "<NAMESPACE>";

    private AnonymizeUtils() {
    }

    public static String anonymize(String string) {
        return anonymizeEmail(
                anonymizeUserName(
                        anonymizeIP(
                                anonymizeHomeDir(
                                        anonymizeTmpDir(string)
                                )
                        )
                )
        );
    }

    public static String anonymizeResource(String name, String namespace, String string) {
        if (string == null
                || string.isEmpty()) {
            return string;
        }
        if (name != null) {
            string = string.replace(name, ANONYMOUS_RESOURCENAME);
        }
        if (namespace != null) {
            string = string.replace(namespace, ANONYMOUS_NAMESPACE);
        }
        return string;
    }

    public static String anonymizeUserName(String string) {
        if (string == null
                || string.isEmpty()) {
            return string;
        }
        return string.replace(USER_NAME, ANONYMOUS_USER_NAME);
    }

    public static String anonymizeEmail(String string) {
        if (string == null
                || string.isEmpty()) {
            return string;
        }
        return EMAIL_PATTERN.matcher(string).replaceAll(ANONYMOUS_EMAIL);
    }

    public static String anonymizeHomeDir(String string) {
        if (string == null
                || string.isEmpty()) {
            return string;
        }
        return string.replace(HOME_DIR, ANONYMOUS_HOMEDIR);
    }

    public static String anonymizeTmpDir(String string) {
        if (string == null
                || string.isEmpty()) {
            return string;
        }
        return string.replace(TMP_DIR, ANONYMOUS_TMPDIR);
    }

    public static String anonymizeIP(String string) {
        if (string == null
                || string.isEmpty()) {
            return string;
        }
        return IP_PATTERN.matcher(string).replaceAll(ANONYMOUS_IP);
    }

}

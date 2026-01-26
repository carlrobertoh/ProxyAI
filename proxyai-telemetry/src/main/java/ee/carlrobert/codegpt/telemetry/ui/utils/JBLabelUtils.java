/*******************************************************************************
 * Copyright (c) 2021 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 * Red Hat, Inc.
 ******************************************************************************/
package ee.carlrobert.codegpt.telemetry.ui.utils;

import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.ui.ColorUtil;
import java.awt.Color;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import javax.swing.JLabel;

public class JBLabelUtils {

    private static final Logger LOGGER = Logger.getInstance(JBLabelUtils.class);

    private JBLabelUtils() {
    }

    /**
     * Turns the given text to html that a JBLabel can display (with links).
     *
     * @param text the text to convert to Html
     * @param maxLineLength the maximum number of characters in a line
     * @param component the component to retrieve the font metrics from
     *
     * @returns the text
     */
    public static String toStyledHtml(String text, int maxLineLength, JLabel component) {
        String css = "<head><style type=\"text/css\">\n" +
                "a, a:link {color:#" + toHex(getColor(
                        "com.intellij.util.ui.JBUI$CurrentTheme$Link", "linkColor",
                        "com.intellij.util.ui.JBUI$CurrentTheme$Link$Foreground", "ENABLED")) + ";}\n" +
                "a:visited {color:#" + toHex(getColor(
                        "com.intellij.util.ui.JBUI$CurrentTheme$Link", "linkVisitedColor",
                        "com.intellij.util.ui.JBUI$CurrentTheme$Link$Foreground", "VISITED")) + ";}\n" +
                "a:hover {color:#" + toHex(getColor(
                "com.intellij.util.ui.JBUI$CurrentTheme$Link", "linkHoverColor",
                "com.intellij.util.ui.JBUI$CurrentTheme$Link$Foreground", "HOVERED")) + ";}\n" +
                "a:active {color:#" + toHex(getColor(
                        "com.intellij.util.ui.JBUI$CurrentTheme$Link", "linkPressedColor",
                        "com.intellij.util.ui.JBUI$CurrentTheme$Link$Foreground", "PRESSED")) + ";}\n" +
                "</style>\n</head>";
        int width = component.getFontMetrics(component.getFont()).stringWidth(text.substring(0, maxLineLength));
        return "<html>" + css + "<body><div width=" + width + ">" + text + "</div></body></html>";
    }

    private static Color getColor(String methodClass, String method, String fieldClass, String field) {
        try {
            // < IC-2022.1
            Class clazz = Class.forName(methodClass);
            Method colorMethod = clazz.getMethod(method);
            return (Color) colorMethod.invoke(null);
        } catch (ClassNotFoundException | NoSuchMethodException | InvocationTargetException | IllegalAccessException | ClassCastException e) {
            // >= IC-2022.1
            try {
                Class clazz = Class.forName(fieldClass);
                Field colorField = clazz.getDeclaredField(field);
                return (Color) colorField.get(null);
            } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException | ClassCastException ex) {
                LOGGER.warn("Could not retrieve link colors from "
                        + methodClass + "/" + method + " or " + fieldClass + "/" + field
                        + "on IC-" + ApplicationInfo.getInstance().getBuild().asStringWithoutProductCode(), ex);
            }
            return null;
        }
    }

    private static String toHex(Color color) {
        if (color == null) {
            return "";
        }
        return ColorUtil.toHex(color);
    }
}

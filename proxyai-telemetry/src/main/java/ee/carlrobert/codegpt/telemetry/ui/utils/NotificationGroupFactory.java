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
package ee.carlrobert.codegpt.telemetry.ui.utils;

import com.intellij.notification.NotificationDisplayType;
import com.intellij.notification.NotificationGroup;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.diagnostic.Logger;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * A factory to create {@link NotificationGroup} on &gt;= IC-2019.3 and &gt;= IC-2021.3 where the API was broken.
 *
 * <ul>
 *     <li><b>IC-2019.3</b>
 *      <pre>{@code
 *          new NotificationGroup(String displayId, NotificationDisplayType defaultDisplayType, boolean logByDefault)
 *      }</pre>
 *     </li>
 *     <li><b></b>IC-2021.3</b>
 *      <pre>{@code
 *          NotificationGroupManager.getInstance().getNotificationGroup(displayId)
 *      }</pre>
 *     </li>
 * </ul>
 */
public class NotificationGroupFactory {

    public static NotificationGroup create(String displayId, NotificationDisplayType type, boolean logByDefault) {
        try {
            // < IC-2021.3
            // new NotificationGroup(String displayId, NotificationDisplayType defaultDisplayType, boolean logByDefault)
            Constructor<NotificationGroup> constructor = NotificationGroup.class.getConstructor(String.class, NotificationDisplayType.class, boolean.class);
            return constructor.newInstance(displayId, type, logByDefault);
        } catch (NoSuchMethodException | InvocationTargetException | InstantiationException | IllegalAccessException e) {
            // >= IC-2021.3
            // constructor removed >= 2021.3
            try {
                // NotificationGroupManager.getInstance().getNotificationGroup(displayId)
                Class<?> managerClass = Class.forName("com.intellij.notification.NotificationGroupManager");
                Method getInstance = managerClass.getMethod("getInstance");
                Object manager = getInstance.invoke(null);
                if (manager == null) {
                    return null;
                }
                Method getNotificationGroup = managerClass.getMethod("getNotificationGroup", String.class);
                Object group = getNotificationGroup.invoke(manager, displayId);
                if (!(group instanceof NotificationGroup)) {
                    return null;
                }
                return (NotificationGroup) group;
            } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InvocationTargetException ex) {
                Logger.getInstance(NotificationGroupFactory.class).warn("Could not create NotificationGroup for IC-"
                        + ApplicationInfo.getInstance().getBuild().asStringWithoutProductCode());
                return null;
            }
        }
    }

}

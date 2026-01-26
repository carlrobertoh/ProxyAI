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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TimeUtils {

    private static final Pattern HH_MM_SS_DURATION = Pattern.compile("([\\d]+):([\\d]{2}):([\\d]{2})");

    private TimeUtils() {}

    /**
     * Returns a {@link LocalTime} for a given milliseconds.
     *
     * @param millis
     * @return
     */
    public static LocalDateTime toLocalTime(long millis) {
        Instant instant = new Date(millis).toInstant();
        ZonedDateTime time = instant.atZone(ZoneId.systemDefault());
        return time.toLocalDateTime();
    }

    /**
     * Returns a human readable string representation in the format "HH:MM:SS" for a given duration.
     *
     * @param duration to be transformed to its human readable form
     *
     * @return a string "HH:MM:SS" representing the duration
     */
    public static String toString(Duration duration) {
        return String.format("%02d:%02d:%02d",
                duration.toHours(),
                duration.toMinutes() % 60,
                duration.getSeconds() % 60);
    }

    /**
     * Returns the duration for a given string "HH:MM:SS".
     * It's the reverse operation for #toString(Duration).
     * 
     * @param hoursMinutesSeconds
     * @return the duration
     * 
     * @see #toString(Duration)
     */
    public static Duration toDuration(String hoursMinutesSeconds) {
        Matcher matcher = HH_MM_SS_DURATION.matcher(hoursMinutesSeconds);
        if (!matcher.matches()) {
            return null;
        }
        long hours = Integer.parseInt(matcher.group(1));
        Duration duration = Duration.ofHours(hours);
        long minutes = Integer.parseInt(matcher.group(2));
        duration = duration.plusMinutes(minutes);
        long seconds = Integer.parseInt(matcher.group(3));
        duration = duration.plusSeconds(seconds);
        return duration;
    }

}

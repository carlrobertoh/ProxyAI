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
package ee.carlrobert.codegpt.telemetry.core.service;

import java.util.Locale;
import java.util.Objects;
import java.util.TimeZone;

public class Environment {

    public static final String UNKNOWN_COUNTRY = "ZZ";

    private final Plugin plugin;
    private final IDE ide;
    private final Platform platform;
    private final String timezone;
    private final String locale;
    private final String country;

    private Environment(Plugin plugin, IDE ide, Platform platform, String timezone, String locale, String country) {
        this.plugin = plugin;
        this.ide = ide;
        this.platform = platform;
        this.timezone = timezone;
        this.locale = locale;
        this.country = country;
    }

    public static class Builder {

        private IDE ide;
        private Plugin plugin;
        private Platform platform;
        private String timezone;
        private String locale;
        private String country;

        public Builder ide(IDE ide) {
            this.ide = ide;
            return this;
        }

        private void ensureIDE() {
            if (ide == null) {
                ide(new IDE.Factory().create());
            }
        }

        public Buildable plugin(ClassLoader classLoader) {
            return plugin(new Plugin.Factory().create(classLoader));
        }

        public Buildable plugin(Plugin plugin) {
            this.plugin = plugin;
            return new Buildable();
        }

        public Builder platform(Platform platform) {
            this.platform = platform;
            return this;
        }

        private void ensurePlatform() {
            if (platform == null) {
                platform(new Platform());
            }
        }

        public Builder timezone(String timezone) {
            this.timezone = timezone;
            return this;
        }

        private void ensureTimezone() {
            if (timezone == null) {
                timezone(TimeZone.getDefault().getID());
            }
        }

        public Builder locale(String locale) {
            this.locale = locale;
            return this;
        }

        private void ensureLocale() {
            if (locale == null) {
                locale(Locale.getDefault().toString().replace('_', '-'));
            }
        }

        public Builder country(String country) {
            this.country = country;
            return this;
        }

        private void ensureCountry() {
            if (this.country == null) {
                /*
                 * We're not allowed to query 3rd party services to determine the country.
                 * Segment won't report countries for incoming requests.
                 * We thus currently dont have any better solution than use the country in the Locale.
                 */
                ensureTimezone();
                String country = Country.getInstance().get(timezone);
                if (country == null) {
                    country = UNKNOWN_COUNTRY;
                }
                country(country);
            }
        }

        public class Buildable {
            public Environment build() {
                ensureIDE();
                ensurePlatform();
                ensureCountry();
                ensureLocale();
                ensureTimezone();
                return new Environment(plugin, ide, platform, timezone, locale, country);
            }
        }
    }

    /**
     * Returns the plugin from which Telemetry events are sent.
     */
    public Application getPlugin() {
        return plugin;
    }

    /**
     * Returns the application from which Telemetry events are sent .
     */
    public IDE getIde() {
        return ide;
    }

    /**
     * Returns the platform (or OS) from from which Telemetry events are sent.
     */
    public Platform getPlatform() {
        return platform;
    }

    /**
     * Returns the user timezone, eg. 'Europe/Paris'
     */
    public String getTimezone() {
        return timezone;
    }

    /**
     * Returns the user locale, eg. 'en-US'
     */
    public String getLocale() {
        return locale;
    }

    public String getCountry() {
        return country;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Environment that)) return false;
      return Objects.equals(plugin, that.plugin)
                && Objects.equals(ide, that.ide)
                && Objects.equals(platform, that.platform)
                && Objects.equals(timezone, that.timezone)
                && Objects.equals(locale, that.locale)
                && Objects.equals(country, that.country);
    }

    @Override
    public int hashCode() {
        return Objects.hash(plugin, ide, platform, timezone, locale, country);
    }

}

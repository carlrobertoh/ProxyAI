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
package ee.carlrobert.codegpt.telemetry.core.service.segment;

import ee.carlrobert.codegpt.telemetry.core.configuration.ClasspathConfiguration;
import ee.carlrobert.codegpt.telemetry.core.configuration.CompositeConfiguration;
import ee.carlrobert.codegpt.telemetry.core.configuration.IConfiguration;
import ee.carlrobert.codegpt.telemetry.core.configuration.SystemProperties;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

public class SegmentConfiguration extends CompositeConfiguration implements ISegmentConfiguration {

    public static final String KEY_SEGMENT_WRITE = "writeKey";
    public static final String KEY_SEGMENT_DEBUG_WRITE = "debugWriteKey";

    private static final String SEGMENT_PROPERTIES = "segment.properties";
    private static final String SEGMENT_DEFAULTS_PROPERTIES = "segment-defaults.properties";

    private final ClasspathConfiguration consumerClasspathConfiguration;

    public SegmentConfiguration(ClassLoader classLoader) {
        this(new ClasspathConfiguration(Paths.get(SEGMENT_PROPERTIES), classLoader));
    }

    protected SegmentConfiguration(ClasspathConfiguration consumerClasspathConfiguration) {
        this.consumerClasspathConfiguration = consumerClasspathConfiguration;
    }

    @Override
    public void put(String key, String value) {
        consumerClasspathConfiguration.put(key, value);
    }

    @Override
    public List<IConfiguration> getConfigurations() {
        return Arrays.asList(
                new SystemProperties(),
                // segment.properties in consuming plugin
                consumerClasspathConfiguration,
                // segment-defaults.properties in this plugin
                new ClasspathConfiguration(Paths.get(SEGMENT_DEFAULTS_PROPERTIES), getClass().getClassLoader()));
    }

    @Override
    public String getNormalWriteKey() {
        return get(KEY_SEGMENT_WRITE);
    }

    @Override
    public String getDebugWriteKey() {
        return get(KEY_SEGMENT_DEBUG_WRITE);
    }
}

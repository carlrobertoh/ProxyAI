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
package ee.carlrobert.codegpt.telemetry.core.configuration;

import java.io.InputStream;
import java.nio.file.Path;

public class ClasspathConfiguration extends FileConfiguration {

	private final ClassLoader classloader;

	public ClasspathConfiguration(Path file) {
		this(file, ClasspathConfiguration.class.getClassLoader());
	}

	public ClasspathConfiguration(Path file, ClassLoader classLoader) {
		super(file);
		this.classloader = classLoader;
	}

	@Override
	protected InputStream createInputStream(Path path) {
		if (path == null) {
			return null;
		}
		return classloader.getResourceAsStream(path.toString());
	}
}

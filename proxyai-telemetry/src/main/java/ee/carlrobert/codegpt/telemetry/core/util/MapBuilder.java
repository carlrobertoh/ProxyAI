/*******************************************************************************
 * Copyright (c) 2022 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 * Red Hat, Inc. - initial API and implementation
 ******************************************************************************/
package ee.carlrobert.codegpt.telemetry.core.util;

import java.util.AbstractMap;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class MapBuilder {

    private final Map<String, Object> map = new HashMap<>();

    public MapBuilder pair(String key, String value) {
        map.put(key, value);
        return this;
    }

    public MapBuilder pair(String key, Object value) {
        map.put(key, value);
        return this;
    }

    public MapBuilder pairs(Collection<AbstractMap.SimpleEntry<String, Object>> entries) {
        if (entries != null) {
            entries.forEach(entry -> map.put(entry.getKey(), entry.getValue()));
        }
        return this;
    }

    public MapValueBuilder mapPair(String key) {
        return new MapValueBuilder(key);
    }

    public Map<String, Object> build() {
        return map;
    }

    public class MapValueBuilder {
        private final Map<String, Object> map = new HashMap<>();
        private final String key;

        private MapValueBuilder(String key) {
            this.key = key;
        }

        public MapValueBuilder pair(String key, Object value) {
            map.put(key, value);
            return this;
        }

        public MapValueBuilder pairs(Collection<AbstractMap.SimpleEntry<String, Object>> entries) {
            if (entries != null) {
                entries.forEach(entry -> map.put(entry.getKey(), entry.getValue()));
            }
            return this;
        }

        public MapBuilder build() {
            MapBuilder.this.pair(key, map);
            return MapBuilder.this;
        }
    }
}

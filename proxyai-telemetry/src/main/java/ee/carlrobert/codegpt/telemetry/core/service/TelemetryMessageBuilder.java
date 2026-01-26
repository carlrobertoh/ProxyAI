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

import static ee.carlrobert.codegpt.telemetry.core.service.TelemetryEvent.Type.ACTION;
import static ee.carlrobert.codegpt.telemetry.core.service.TelemetryEvent.Type.SHUTDOWN;
import static ee.carlrobert.codegpt.telemetry.core.service.TelemetryEvent.Type.STARTUP;
import static ee.carlrobert.codegpt.telemetry.core.util.TimeUtils.toLocalTime;

import com.intellij.ide.AppLifecycleListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.messages.MessageBusConnection;
import ee.carlrobert.codegpt.telemetry.core.ITelemetryService;
import ee.carlrobert.codegpt.telemetry.core.service.TelemetryEvent.Type;
import ee.carlrobert.codegpt.telemetry.core.util.AnonymizeUtils;
import ee.carlrobert.codegpt.telemetry.core.util.TimeUtils;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

public class TelemetryMessageBuilder {

    private static final Logger LOGGER = Logger.getInstance(TelemetryMessageBuilder.class);

    private final ServiceFacade service;

    public TelemetryMessageBuilder(ClassLoader classLoader) {
        this(new ServiceFacade(classLoader));
    }

    TelemetryMessageBuilder(ServiceFacade serviceFacade) {
        this.service = serviceFacade;
    }

    public ActionMessage action(String name) {
        return new ActionMessage(name, service);
    }

    static class StartupMessage extends Message<StartupMessage> {

        private StartupMessage(ServiceFacade service) {
            super(STARTUP, "startup", service);
        }
    }

    static class ShutdownMessage extends Message<ShutdownMessage> {

        private static final String PROP_SESSION_DURATION = "session_duration";

        private ShutdownMessage(ServiceFacade service) {
            this(toLocalTime(ApplicationManager.getApplication().getStartTime()), service);
        }

        private ShutdownMessage(LocalDateTime startup, ServiceFacade service) {
            this(startup, LocalDateTime.now(), service);
        }

        ShutdownMessage(LocalDateTime startup, LocalDateTime shutdown, ServiceFacade service) {
            super(SHUTDOWN, "shutdown", service);
            sessionDuration(startup, shutdown);
        }

        private ShutdownMessage sessionDuration(LocalDateTime startup, LocalDateTime shutdown) {
            return sessionDuration(Duration.between(startup, shutdown));
        }

        private ShutdownMessage sessionDuration(Duration duration) {
            return property(PROP_SESSION_DURATION, TimeUtils.toString(duration));
        }

        String getSessionDuration() {
            return getProperty(PROP_SESSION_DURATION);
        }
    }

    public static class ActionMessage extends Message<ActionMessage> {

        static final String PROP_DURATION = "duration";
        static final String PROP_ERROR = "error";
        static final String PROP_RESULT = "result";

        public static final String RESULT_SUCCESS = "success";

        private LocalDateTime started;

        private ActionMessage(String name, ServiceFacade service) {
            super(ACTION, name, service);
            started();
        }

        public ActionMessage started() {
            return started(LocalDateTime.now());
        }

        public ActionMessage started(LocalDateTime started) {
            this.started = started;
            return this;
        }

        public ActionMessage finished() {
            finished(LocalDateTime.now());
            return this;
        }

        public ActionMessage finished(LocalDateTime finished) {
            duration(Duration.between(started, finished));
            return this;
        }

        public ActionMessage duration(Duration duration) {
            return property(PROP_DURATION, TimeUtils.toString(duration));
        }

        String getDuration() {
            return getProperty(PROP_DURATION);
        }

        public ActionMessage success() {
            return result(RESULT_SUCCESS);
        }

        public ActionMessage result(String result) {
            property(PROP_RESULT, result);
            return clearError();
        }

        protected ActionMessage clearResult() {
            properties().remove(PROP_RESULT);
            return this;
        }

        String getResult() {
            return getProperty(PROP_RESULT);
        }

        public ActionMessage error(Exception exception) {
            if (exception == null) {
                return this;
            }
            return error(exception.getMessage());
        }

        public ActionMessage error(String message) {
            property(PROP_ERROR, AnonymizeUtils.anonymize(message));
            return clearResult();
        }

        protected ActionMessage clearError() {
            properties().remove(PROP_ERROR);
            return this;
        }

        String getError() {
            return getProperty(PROP_ERROR);
        }

        @Override
        public TelemetryEvent send() {
            ensureFinished();
            ensureResultOrError();
            return super.send();
        }

        private void ensureFinished() {
            if (!hasProperty(PROP_DURATION)) {
                finished();
            }
        }

        private void ensureResultOrError() {
            if (!hasProperty(PROP_ERROR)
                    && !hasProperty(PROP_RESULT)) {
                success();
            }
        }
    }

    private abstract static class Message<T extends Message<?>> {

        private final Type type;
        private final Map<String, String> properties = new HashMap<>();
        private final String name;
        private final ServiceFacade service;

        private Message(Type type, String name, ServiceFacade service) {
            this.name = name;
            this.type = type;
            this.service = service;
        }

        String getName() {
            return name;
        }

        Type getType() {
            return type;
        }

        public T property(String key, String value) {
            if (key == null
                    || value == null) {
                LOGGER.warn("Ignored property with key: " + key + " value: " + value);
            } else {
                properties.put(key, value);
            }
            return (T) this;
        }

        String getProperty(String key) {
            return properties.get(key);
        }

        Map<String, String> properties() {
            return properties;
        }

        protected boolean hasProperty(String key) {
            return properties.containsKey(key);
        }

        public TelemetryEvent send() {
            TelemetryEvent event = new TelemetryEvent(type, name, new HashMap<>(properties));
            service.send(event);
            return event;
        }
    }

    static class ServiceFacade {
        private final ClassLoader classLoader;
        private ITelemetryService service = null;

        protected ServiceFacade(final ClassLoader classLoader) {
            this.classLoader = classLoader;
        }

        public void send(final TelemetryEvent event) {
            if (service == null) {
                this.service = createService(classLoader);
                sendStartup();
                onShutdown();
            }
            service.send(event);
        }

        protected ITelemetryService createService(ClassLoader classLoader) {
            TelemetryServiceFactory factory = ApplicationManager.getApplication().getService(TelemetryServiceFactory.class);
            return factory.create(classLoader);
        }

        private void sendStartup() {
            new StartupMessage(this).send();
        }

        private void onShutdown() {
            MessageBusConnection connection = createMessageBusConnection();
            connection.subscribe(AppLifecycleListener.TOPIC, new AppLifecycleListener() {
                @Override
                public void appWillBeClosed(boolean isRestart) {
                    sendShutdown();
                }
            });
        }

        protected void sendShutdown() {
            new ShutdownMessage(ServiceFacade.this).send();
        }

        protected MessageBusConnection createMessageBusConnection() {
            return ApplicationManager.getApplication().getMessageBus().connect();
        }

    }
}

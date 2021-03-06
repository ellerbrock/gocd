/*************************GO-LICENSE-START*********************************
 * Copyright 2014 ThoughtWorks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *************************GO-LICENSE-END***********************************/

package com.thoughtworks.go.remote.work;

import com.thoughtworks.go.util.SystemEnvironment;
import com.thoughtworks.go.util.command.TaggedStreamConsumer;
import org.apache.commons.collections.buffer.CircularFifoBuffer;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static java.lang.String.format;

public final class ConsoleOutputTransmitter implements TaggedStreamConsumer, Runnable {
    private static final Logger LOGGER = Logger.getLogger(ConsoleOutputTransmitter.class);

    private final CircularFifoBuffer buffer = new CircularFifoBuffer(10 * 1024); // maximum 10k lines
    private final ConsoleAppender consoleAppender;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss.SSS");
    private final ScheduledThreadPoolExecutor executor;

    public ConsoleOutputTransmitter(ConsoleAppender consoleAppender) {
        this(consoleAppender, new SystemEnvironment().getConsolePublishInterval(), new ScheduledThreadPoolExecutor(1));
    }

    protected ConsoleOutputTransmitter(ConsoleAppender consoleAppender, Integer consolePublishInterval,
                                       ScheduledThreadPoolExecutor scheduledThreadPoolExecutor) {
        this.consoleAppender = consoleAppender;
        this.executor = scheduledThreadPoolExecutor;
        executor.scheduleAtFixedRate(this, 0L, consolePublishInterval, TimeUnit.SECONDS);

    }

    public void consumeLine(String line) {
        taggedConsumeLine(null, line);
    }

    @Override
    public void taggedConsumeLine(String tag, String line) {
        synchronized (buffer) {
            if (null == tag) tag = "  ";
            String date = dateFormat.format(new Date());
            String prepend = format("%s|%s", tag, date);
            String multilineJoin = "\n" + prepend + " ";
            buffer.add(format("%s %s", prepend, line).replaceAll("\n", multilineJoin));
        }
    }

    public void run() {
        try {
            flushToServer();
        } catch (Throwable e) {
            LOGGER.warn("Could not send console output to server", e);
        }
    }

    public void flushToServer() {
        if (buffer.isEmpty()) {
            return;
        }

        List sent = new ArrayList();
        try {
            synchronized (buffer) {
                while (!buffer.isEmpty()) {
                    sent.add(buffer.remove());
                }
            }
            StringBuilder result = new StringBuilder();
            for (Object string : sent) {
                result.append(string);
                result.append("\n");
            }
            consoleAppender.append(result.toString());
        } catch (IOException e) {
            LOGGER.warn("Could not send console output to server", e);
            synchronized (buffer) {
                sent.addAll(buffer);
                buffer.clear();
                buffer.addAll(sent);
            }
        }
    }

    public void stop() {
        flushToServer();
        executor.shutdown();
    }
}

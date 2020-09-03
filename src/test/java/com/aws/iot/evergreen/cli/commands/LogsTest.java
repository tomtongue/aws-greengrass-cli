/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.cli.commands;

import com.aws.iot.evergreen.cli.CLI;
import com.aws.iot.evergreen.cli.TestUtil;
import com.aws.iot.evergreen.cli.adapter.AdapterModule;
import com.aws.iot.evergreen.cli.util.logs.LogsModule;
import com.aws.iot.evergreen.cli.util.logs.LogsUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;

import static com.aws.iot.evergreen.cli.TestUtil.deleteDir;
import static com.aws.iot.evergreen.cli.util.logs.impl.VisualizationImpl.ANSI_HIGHLIGHT;
import static com.aws.iot.evergreen.cli.util.logs.impl.VisualizationImpl.ANSI_HIGHLIGHT_RESET;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;


public class LogsTest {
    private static final String logEntry0 = "{\"thread\":\"idle-connection-reaper\",\"level\":\"DEBUG\","
            + "\"eventType\":\"null\",\"message\":\"Closing connections idle longer than 80000 MILLISECONDS\","
            + "\"timestamp\":0,\"cause\":null}";

    private static final String logEntry1 = "{\"thread\":\"idle-connection-reaper\",\"level\":\"DEBUG\","
            + "\"eventType\":\"null\",\"message\":\"Closing connections idle longer than 60000 MILLISECONDS\","
            + "\"timestamp\":1594836028088,\"cause\":null}";

    private static final String logEntry2 = "{\"thread\":\"idle-connection-reaper\",\"level\":\"DEBUG\","
            + "\"eventType\":\"null\",\"message\":\"Closing connections idle longer than 70000 MILLISECONDS\","
            + "\"timestamp\":1594836028090,\"cause\":null}";

    @TempDir
    Path logDir;
    private Path logFile;

    private ByteArrayOutputStream byteArrayOutputStream;
    private PrintStream printStream;
    private PrintStream fileWriter;

    @BeforeEach
    void init() throws FileNotFoundException {
        logFile = logDir.resolve("evergreen.log");
        byteArrayOutputStream = new ByteArrayOutputStream();
        printStream = TestUtil.createPrintStreamFromOutputStream(byteArrayOutputStream);
        LogsUtil.setPrintStream(printStream);
        fileWriter = TestUtil.createPrintStreamFromOutputStream(new FileOutputStream(logFile.toFile()));
    }

    @Test
    void testListLogFileHappyCase() {
        fileWriter.println(logEntry1);
        runCommandLine("logs", "list-log-files", "--log-dir", logDir.toString());

        assertThat(TestUtil.byteArrayOutputStreamToString(byteArrayOutputStream),
                containsString(logFile.toString() + "\n" + "Total 1 files found."));
    }

    @Test
    void testGetHappyCase() throws InterruptedException {
        fileWriter.println(logEntry1);

        Thread thread = new Thread(() -> runCommandLine("logs", "get", "--log-file", logFile.toString(),
                "--filter", "level=DEBUG", "--filter", "thread=idle-connection-reaper", "--filter", "60000",
                "--time-window", "2020-07-14T02:00:00,2020-07-16T03:00:00", "--verbose", "--no-color"));

        thread.start();
        thread.join();
        assertThat(TestUtil.byteArrayOutputStreamToString(byteArrayOutputStream), containsString("[DEBUG] "
                + "(idle-connection-reaper) null: null. Closing connections idle longer than 60000 MILLISECONDS"));
    }

    @Test
    void testGetHighLightCase() throws InterruptedException {
        fileWriter.println(logEntry1);

        Thread thread = new Thread(() -> runCommandLine("logs", "get", "--log-file", logFile.toString(),
                "--filter", "level=DEBUG", "--filter", "thread=idle-connection-reaper", "--filter", "bad,60000",
                "--time-window", "2020-07-14T02:00:00,2020-07-16T03:00:00", "--verbose"));

        thread.start();
        thread.join();
        assertThat(TestUtil.byteArrayOutputStreamToString(byteArrayOutputStream), containsString("[" + ANSI_HIGHLIGHT
                + "DEBUG" + ANSI_HIGHLIGHT_RESET +"] " + "(" + ANSI_HIGHLIGHT + "idle-connection-reaper" + ANSI_HIGHLIGHT_RESET
                + ") null: null. Closing connections idle longer than " + ANSI_HIGHLIGHT +"60000" + ANSI_HIGHLIGHT_RESET + " MILLISECONDS"));
    }

    @Test
    void testGetFollowHappyCase() throws InterruptedException {
        fileWriter.println(logEntry1);
        Thread thread = new Thread(() -> runCommandLine("logs", "get", "--log-file", logFile.toString(),
                "--time-window", "2020-07-14T02:00:00,+1s", "--follow", "--verbose", "--no-color"));
        thread.start();
        // we wait for 500ms to write more entries to the file to test the follow option.
        fileWriter.println(logEntry2);
        fileWriter.println(logEntry0);
        thread.join();

        assertThat(TestUtil.byteArrayOutputStreamToString(byteArrayOutputStream), containsString("[DEBUG] "
                + "(idle-connection-reaper) null: null. Closing connections idle longer than 60000 MILLISECONDS"));
        assertThat(TestUtil.byteArrayOutputStreamToString(byteArrayOutputStream), containsString("[DEBUG] "
                + "(idle-connection-reaper) null: null. Closing connections idle longer than 70000 MILLISECONDS"));
        assertFalse(TestUtil.byteArrayOutputStreamToString(byteArrayOutputStream).contains("80000"));
    }

    @Test
    void testGetFollowMultipleGroupCase() throws InterruptedException, FileNotFoundException {
        Path logFile2 = logDir.resolve("xxx.log");
        Thread thread = new Thread(() -> runCommandLine("logs", "get", "--log-file", logFile.toString(),
                "--log-file", logFile2.toString(), "--time-window", "2020-07-14T02:00:00,+1s",
                "--follow", "--verbose", "--no-color"));
        thread.start();
        fileWriter.println(logEntry1);

        PrintStream fileWriter2 = TestUtil.createPrintStreamFromOutputStream(new FileOutputStream(logFile2.toFile()));
        fileWriter2.println(logEntry2);
        thread.join();

        assertThat(TestUtil.byteArrayOutputStreamToString(byteArrayOutputStream), containsString("[DEBUG] "
                + "(idle-connection-reaper) null: null. Closing connections idle longer than 60000 MILLISECONDS"));
        assertThat(TestUtil.byteArrayOutputStreamToString(byteArrayOutputStream), containsString("[DEBUG] "
                + "(idle-connection-reaper) null: null. Closing connections idle longer than 70000 MILLISECONDS"));
    }

    @Test
    void testGetBeforeHappyCase() throws InterruptedException {
        fileWriter.println(logEntry2);
        fileWriter.println(logEntry1);
        Thread thread = new Thread(() -> runCommandLine("logs", "get", "--log-file", logFile.toString(),
                "--before", "1", "--verbose", "--no-color", "--filter", "60000"));
        thread.start();
        thread.join();
        assertThat(TestUtil.byteArrayOutputStreamToString(byteArrayOutputStream), containsString("[DEBUG] "
                + "(idle-connection-reaper) null: null. Closing connections idle longer than 60000 MILLISECONDS"));
        assertThat(TestUtil.byteArrayOutputStreamToString(byteArrayOutputStream), containsString("[DEBUG] "
                + "(idle-connection-reaper) null: null. Closing connections idle longer than 70000 MILLISECONDS"));
    }

    @Test
    void testGetAfterHappyCase() throws InterruptedException {
        fileWriter.println(logEntry2);
        fileWriter.println(logEntry1);
        Thread thread = new Thread(() -> runCommandLine("logs", "get", "--log-file", logFile.toString(),
                "--after", "1", "--verbose", "--no-color", "--filter", "70000"));
        thread.start();
        thread.join();
        assertThat(TestUtil.byteArrayOutputStreamToString(byteArrayOutputStream), containsString("[DEBUG] "
                + "(idle-connection-reaper) null: null. Closing connections idle longer than 60000 MILLISECONDS"));
        assertThat(TestUtil.byteArrayOutputStreamToString(byteArrayOutputStream), containsString("[DEBUG] "
                + "(idle-connection-reaper) null: null. Closing connections idle longer than 70000 MILLISECONDS"));
    }

    @AfterEach
    void cleanup() {
        deleteDir(logDir.toFile());
        printStream.close();
        fileWriter.close();
    }

    private void runCommandLine(String... args) {
        new CommandLine(new CLI(), new CLI.GuiceFactory(new AdapterModule(), new LogsModule())).execute(args);
    }
}
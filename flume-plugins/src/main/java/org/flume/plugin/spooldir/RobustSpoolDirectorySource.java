package org.flume.plugin.spooldir;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.flume.*;
import org.apache.flume.conf.Configurable;
import org.apache.flume.instrumentation.SourceCounter;
import org.apache.flume.serialization.LineDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;

import static org.apache.flume.source.SpoolDirectorySourceConfigurationConstants.*;
import org.apache.flume.source.AbstractSource;
/**
 * Created by weiguo on 14-1-25.
 */
public class RobustSpoolDirectorySource extends AbstractSource implements
        Configurable, EventDrivenSource {
    private static final Logger logger = LoggerFactory.getLogger(RobustReliableSpoolingFileEventReader.class);
    private static final int POLL_DELAY_MS = 500;

    /* Config options */
    private String completedSuffix;
    private String spoolDirectory;
    private boolean fileHeader;
    private String fileHeaderKey;
    private int batchSize;
    private String ignorePattern;
    private String trackerDirPath;
    private String deserializerType;
    private Context deserializerContext;
    private String deletePolicy;
    private String inputCharset;

    private SourceCounter sourceCounter;
    RobustReliableSpoolingFileEventReader reader;

    @Override
    public void start() {
        logger.info("RobustSpoolDirectorySource start()");

        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        File directory = new File(spoolDirectory);
        try {
            reader = new RobustReliableSpoolingFileEventReader.Builder()
                    .spoolDirectory(directory)
                    .completedSuffix(completedSuffix)
                    .ignorePattern(ignorePattern)
                    .trackerDirPath(trackerDirPath)
                    .annotateFileName(fileHeader)
                    .fileNameHeader(fileHeaderKey)
                    .deserializerType(deserializerType)
                    .deserializerContext(deserializerContext)
                    .deletePolicy(deletePolicy)
                    .inputCharset(inputCharset)
                    .build();
        } catch (IOException ioe) {
            throw new FlumeException("Error instantiating spooling event parser", ioe);
        }

        Runnable runner = new SpoolDirectoryRunnable(reader, sourceCounter);
        executor.scheduleWithFixedDelay(runner, 0, POLL_DELAY_MS, TimeUnit.MILLISECONDS);

        super.start();
        logger.debug("SpoolDirectorySource source started");
        sourceCounter.start();
    }

    @Override
    public void stop() {
        super.stop();
        sourceCounter.stop();
        logger.info("SpoolDir source {} stopped. Metrics: {}", getName(), sourceCounter);
    }

    @Override
    public void configure(Context context) {
        spoolDirectory = context.getString(SPOOL_DIRECTORY);
        Preconditions.checkState(spoolDirectory != null, "Configuration must specify a spooling directory");

        completedSuffix = context.getString(SPOOLED_FILE_SUFFIX, DEFAULT_SPOOLED_FILE_SUFFIX);
        deletePolicy = context.getString(DELETE_POLICY, DEFAULT_DELETE_POLICY);
        fileHeader = context.getBoolean(FILENAME_HEADER, DEFAULT_FILE_HEADER);
        fileHeaderKey = context.getString(FILENAME_HEADER_KEY, DEFAULT_FILENAME_HEADER_KEY);
        batchSize = context.getInteger(BATCH_SIZE, DEFAULT_BATCH_SIZE);
        inputCharset = context.getString(INPUT_CHARSET, DEFAULT_INPUT_CHARSET);
        ignorePattern = context.getString(IGNORE_PAT, DEFAULT_IGNORE_PAT);
        trackerDirPath = context.getString(TRACKER_DIR, DEFAULT_TRACKER_DIR);
        deserializerType = context.getString(DESERIALIZER, DEFAULT_DESERIALIZER);
        deserializerContext = new Context(context.getSubProperties(DESERIALIZER + "."));

        // "Hack" to support backwards compatibility with previous generation of
        // spooling directory source, which did not support deserializers
        Integer bufferMaxLineLength = context.getInteger(BUFFER_MAX_LINE_LENGTH);
        if (bufferMaxLineLength != null && deserializerType != null &&
                deserializerType.equalsIgnoreCase(DEFAULT_DESERIALIZER)) {
            deserializerContext.put(LineDeserializer.MAXLINE_KEY, bufferMaxLineLength.toString());
        }
        if (sourceCounter == null) {
            sourceCounter = new SourceCounter(getName());
        }
    }

    private class SpoolDirectoryRunnable implements Runnable {
        private RobustReliableSpoolingFileEventReader reader;
        private SourceCounter sourceCounter;

        public SpoolDirectoryRunnable(RobustReliableSpoolingFileEventReader reader,
                                      SourceCounter sourceCounter) {
            this.reader = reader;
            this.sourceCounter = sourceCounter;
        }

        @Override
        public void run() {
            try {
                while (true) {
                    List<Event> events = reader.readEvents(batchSize);
                    if (events.isEmpty()) {
                        break;
                    }
                    sourceCounter.addToEventReceivedCount(events.size());
                    sourceCounter.incrementAppendBatchReceivedCount();

                    getChannelProcessor().processEventBatch(events);
                    reader.commit();
                    sourceCounter.addToEventAcceptedCount(events.size());
                    sourceCounter.incrementAppendBatchAcceptedCount();
                }
            } catch (Throwable t) {
                logger.error("Uncaught exception in Runnable", t);
                if (t instanceof Error) {
                    throw (Error) t;
                }
            }
        }
    }
}
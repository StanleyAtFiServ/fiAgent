package org.fiserv;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.*;

public class AppLogger {
    private static final String LOG_PREFIX = "fiAgent";
    private static final String LOG_SUFFIX = ".log";
    private static final int MAX_LOG_SIZE = 5 * 1024 * 1024; // 5MB
    private static final int LOG_BACKUPS = 1;
    private static boolean isConfigured = false;
    private static FileHandler currentFileHandler;
    private static final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "LogRotator");
                t.setDaemon(true);
                return t;
            }
        });
    /*      Lambda
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "LogRotator");
                t.setDaemon(true);
                return t;
            });
*/

    public static synchronized Logger getLogger(Class<?> clazz) {
        if (!isConfigured) {
            configureRootLogger();
            isConfigured = true;
        }
        return Logger.getLogger(clazz.getName());
    }

    private static void configureRootLogger() {
        try {
            Logger rootLogger = Logger.getLogger("");

            // Remove existing handlers
            for (Handler handler : rootLogger.getHandlers()) {
                rootLogger.removeHandler(handler);
                handler.close();
            }

            // Create initial log file
            rotateLogFile();

            // Set log level
            rootLogger.setLevel(Level.INFO);

            // Start midnight rotation scheduler
            scheduleMidnightRotation();

        } catch (SecurityException | IOException e) {
            System.err.println("Logger configuration failed: " + e.getMessage());
        }
    }

    private static void scheduleMidnightRotation() {
        // Calculate initial delay until next midnight using Calendar
        Calendar calendar = Calendar.getInstance();

        // Set to next day 00:00:00
        calendar.add(Calendar.DAY_OF_MONTH, 1);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);

        Date nextMidnight = calendar.getTime();
        Date now = new Date();

        long initialDelay = nextMidnight.getTime() - now.getTime();
        long period = 24 * 60 * 60 * 1000;  // 24 hours in milliseconds

        // Schedule daily rotation with anonymous Runnable
        scheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    rotateLogFile();
                } catch (IOException e) {
                    Logger.getGlobal().log(Level.SEVERE, "Midnight log rotation failed", e);
                }
            }
        }, initialDelay, period, TimeUnit.MILLISECONDS);
    }

    public static synchronized void rotateLogFile() throws IOException {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String logFileName = LOG_PREFIX + "_" + timestamp + LOG_SUFFIX;

        // Close previous handler
        if (currentFileHandler != null) {
            Logger rootLogger = Logger.getLogger("");
            rootLogger.removeHandler(currentFileHandler);
            currentFileHandler.close();
        }

        // Create new handler
        currentFileHandler = new FileHandler(logFileName, MAX_LOG_SIZE, LOG_BACKUPS, true);
        currentFileHandler.setFormatter(new SimpleFormatter());
        Logger.getLogger("").addHandler(currentFileHandler);

        // Log rotation event
        Logger.getGlobal().info("Log file rotated to: " + logFileName);
    }
}
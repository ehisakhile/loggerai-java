package uk.loggerai.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import uk.loggerai.Hooks;

/**
 * Sends warnings and errors logged through SLF4J/Logback (Spring Boot's
 * default). Spring Boot attaches it to the root logger on its own; elsewhere
 * add it in logback.xml: {@code <appender name="LOGGERAI" class="uk.loggerai.logback.LoggerAIAppender"/>}.
 * An error logged with an exception is also reported as an error, with its stack.
 */
public class LoggerAIAppender extends AppenderBase<ILoggingEvent> {
    @Override
    protected void append(ILoggingEvent e) {
        if (e.getLoggerName() != null && e.getLoggerName().startsWith("uk.loggerai")) return;
        String level = e.getLevel().isGreaterOrEqual(Level.ERROR) ? "ERROR" : e.getLevel().isGreaterOrEqual(Level.WARN) ? "WARNING"
                : e.getLevel().isGreaterOrEqual(Level.INFO) ? "INFO" : "DEBUG";
        if (!Hooks.logs(level)) return;
        IThrowableProxy tp = e.getThrowableProxy();
        Throwable t = tp instanceof ThrowableProxy p ? p.getThrowable() : null;
        String msg = e.getFormattedMessage();
        if (tp != null) msg = msg + " — " + tp.getClassName() + ": " + tp.getMessage();
        Hooks.log(level, e.getLoggerName(), msg, t);
    }
}

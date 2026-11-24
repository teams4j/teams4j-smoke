package smoke;

import java.io.InputStream;

/**
 * The log appenders against the real channel. Expect, per backend: one full card, one saying
 * "Repeated: 4 more", three burst cards of which the first sent says "Dropped: 9", and a last one
 * without either. Markdown in the message must show as written.
 */
public final class LogSmoke {

    /** One of the two backends, reduced to what the scenario needs. */
    private interface Backend extends AutoCloseable {
        void error(String template, Object argument, Throwable thrown);

        void warn(String message);

        void mdc(String key, String value);

        @Override
        void close();
    }

    public static void main(String[] args) throws Exception {
        String which = args.length == 0 ? "" : args[0];
        try (Backend log =
                switch (which) {
                    case "logback" -> logback();
                    case "log4j2" -> log4j2();
                    default -> throw new IllegalArgumentException("usage: logback | log4j2");
                }) {
            log.mdc("traceId", "4bf92f3577b34da6");
            Throwable boom = new IllegalStateException(
                    "payment gateway said \"no\"", new java.io.IOException("connection reset by *peer*"));

            log.warn("below the threshold: must not appear");
            // Markdown in a log line has to show as written.
            log.error("Order {} failed: *not bold* _not italic_ [not a link](https://example.com) 한글", 1, boom);
            for (int order = 2; order <= 5; order++) {
                log.error("Order {} failed: *not bold* _not italic_ [not a link](https://example.com) 한글", order, boom);
            }
            Thread.sleep(6_000);
            log.error("Order {} failed: *not bold* _not italic_ [not a link](https://example.com) 한글", 6, null);

            Thread.sleep(2_000);
            for (int i = 1; i <= 12; i++) {
                log.error("burst " + i + " of 12, value {}", i, null);
            }
            Thread.sleep(6_000);
            log.error("after the burst: nothing repeated or dropped since ({})", "ok", null);
        }
        System.out.println("done: " + which);
    }

    private static Backend logback() throws Exception {
        var context = new ch.qos.logback.classic.LoggerContext();
        var mdc = new ch.qos.logback.classic.util.LogbackMDCAdapter();
        context.setMDCAdapter(mdc);
        var configurator = new ch.qos.logback.classic.joran.JoranConfigurator();
        configurator.setContext(context);
        try (InputStream xml = LogSmoke.class.getResourceAsStream("/smoke-logback.xml")) {
            configurator.doConfigure(xml);
        }
        var logger = context.getLogger("com.acme.orders.OrderService");
        return new Backend() {
            @Override
            public void error(String template, Object argument, Throwable thrown) {
                logger.error(template, argument, thrown);
            }

            @Override
            public void warn(String message) {
                logger.warn(message);
            }

            @Override
            public void mdc(String key, String value) {
                mdc.put(key, value);
            }

            @Override
            public void close() {
                context.stop();
            }
        };
    }

    private static Backend log4j2() throws Exception {
        var context = new org.apache.logging.log4j.core.LoggerContext("smoke");
        try (InputStream xml = LogSmoke.class.getResourceAsStream("/smoke-log4j2.xml")) {
            var source = new org.apache.logging.log4j.core.config.ConfigurationSource(xml);
            context.start(new org.apache.logging.log4j.core.config.xml.XmlConfiguration(context, source));
        }
        var logger = context.getLogger("com.acme.orders.OrderService");
        return new Backend() {
            @Override
            public void error(String template, Object argument, Throwable thrown) {
                logger.error(template, argument, thrown);
            }

            @Override
            public void warn(String message) {
                logger.warn(message);
            }

            @Override
            public void mdc(String key, String value) {
                org.apache.logging.log4j.ThreadContext.put(key, value);
            }

            @Override
            public void close() {
                context.stop();
            }
        };
    }
}

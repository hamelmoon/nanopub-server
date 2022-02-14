package ch.tkuhn.nanopub.server.config;


import org.slf4j.Logger;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import static org.slf4j.LoggerFactory.getLogger;

public class LoggerServletConfig implements ServletContextListener {

    Logger logger = null;

    @Override
    public void contextInitialized(ServletContextEvent servletContextEvent) {
        System.setProperty("org.slf4j.simpleLogger.showDateTime","true");
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel","info");
        System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "yyyy-MM-dd::HH-mm-ss-SSS");
        logger = getLogger(this.getClass().getName());
        logger.debug("contextInitialized");

    }

    @Override
    public void contextDestroyed(ServletContextEvent servletContextEvent) {
        logger.debug("contextDestroyed");
    }

}
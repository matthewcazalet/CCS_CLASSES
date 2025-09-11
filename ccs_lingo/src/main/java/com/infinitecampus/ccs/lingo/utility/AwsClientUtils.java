package com.infinitecampus.ccs.lingo.utility;

import java.lang.reflect.Method;



public class AwsClientUtils  {
    //private static final LogHelper logger = new LogHelper(Configuration.getInstance()).createLogger(AwsClientUtils .class);
    public static void safeClose(Object client, LogHelper logger) {
        if (client == null) return;
        
        try {
            // Check if close() method exists
            Method closeMethod = client.getClass().getMethod("close");
            if (closeMethod != null) {
                closeMethod.invoke(client);
                logger.logDebug(client.getClass().getSimpleName() + " closed successfully");
            }
        } catch (NoSuchMethodException e) {
            // close() doesn't exist in this version - that's OK
            logger.logDebug(client.getClass().getSimpleName() + " does not have close() method - skipping");
        } catch (Exception e) {
            logger.logError("Error closing " + client.getClass().getSimpleName() + ": ", e);
        }
    }
}

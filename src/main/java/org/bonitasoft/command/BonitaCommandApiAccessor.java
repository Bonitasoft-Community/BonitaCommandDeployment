package org.bonitasoft.command;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.logging.Logger;

import org.bonitasoft.engine.api.APIAccessor;
import org.bonitasoft.engine.connector.ConnectorAPIAccessorImpl;
import org.bonitasoft.engine.service.TenantServiceAccessor;

/* ******************************************************************************** */
/*                                                                                  */
/* CommandAPI Control */
/*                                                                                  */
/* use this class if you want to have a ApiAccessor as parameters */
/*                                                                                  */
/* Note: to execute the method, a new thread has to be created, and the main command */
/* thread has to wait. But if you don't want to wait, you can implement the */
/* method "waitAnswer" and return false */
/* ******************************************************************************** */

public abstract class BonitaCommandApiAccessor extends BonitaCommand {

    /**
     * implement this Method
     * 
     * @param verb
     * @param parameters
     * @param tenantId
     * @param apiAccessor
     * @return
     */
    public abstract ExecuteAnswer executeCommandApiAccessor(ExecuteParameters executeParameters, APIAccessor apiAccessor);

    public boolean waitAnswer() {
        return true;
    };

    /**
     * you can implements method like getHelp
     */

    /* ******************************************************************************** */
    /*                                                                                  */
    /** implementation */
    /**
     * this is in the command
     */

    private class RunCommandApi implements Runnable {

        public Long lock = new Long(0);

        ExecuteParameters executeParameters;
        public BonitaCommandApiAccessor bonitaCommandAPI;
        /**
         * we copy the value to ensure at one moment, the BonitaCommandApi does not change it's mind...
         */
        public boolean myParentWaits;

        public ExecuteAnswer executeAnswer;

        public void start() {
            final Thread T = new Thread(this);
            T.start();
        }

        public void run() {
            Logger logger = Logger.getLogger(RunCommandApi.class.getName());

            // create the ApiAccessor
            ConnectorAPIAccessorImpl apiAccessor = new ConnectorAPIAccessorImpl(executeParameters.tenantId);
            try {
                executeAnswer = bonitaCommandAPI.executeCommandApiAccessor(executeParameters, apiAccessor);
            } catch (Exception e) {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                String exceptionDetails = sw.toString();

                logger.severe("GetAvailableHumanTaskList : error:" + e.getMessage() + " at " + exceptionDetails);

            }

            // notify my parent: it wait for me !
            if (myParentWaits) {
                synchronized (lock) {
                    lock.notify();
                }
            }
        }
    } //-------------------------------- end RunCommandApi

    /**
     * command call this method.
     * So, let's create a new thread, and wait for its return
     */
    @Override
    public final ExecuteAnswer executeCommand(ExecuteParameters executeParameters, TenantServiceAccessor serviceAccessor) {
        RunCommandApi runCommandApi = new RunCommandApi();
        runCommandApi.executeParameters = executeParameters;
        runCommandApi.bonitaCommandAPI = this;
        runCommandApi.myParentWaits = waitAnswer();

        runCommandApi.start();
        if (runCommandApi.myParentWaits) {
            // synchronized is mandatory to wait
            synchronized (runCommandApi.lock) {
                try {
                    runCommandApi.lock.wait();
                    return runCommandApi.executeAnswer;
                } catch (InterruptedException e) {
                    logger.severe("BonitaCommandAPI. error " + e.toString());
                }
            }
        }
        return new ExecuteAnswer();

    }

}

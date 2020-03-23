package org.bonitasoft.command;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.logging.Logger;

import org.bonitasoft.command.BonitaCommand.ExecuteAnswer;
import org.bonitasoft.command.BonitaCommand.ExecuteParameters;
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

    /**
     * this method is call when the command is deployed. Additional management can be done then (create tables...)
     * @param executeParameters
     * @param apiAccessor
     * @return
     */
    public ExecuteAnswer afterDeployment(ExecuteParameters executeParameters, APIAccessor apiAccessor) {
        return returnOkAnswer();
    }
    
    /**
     * When the Bonita Server restart, at the first call after the restart, this method is call.
     * It's not immediately after the restart, we have to wait the first call on the command.
     * @param executeParameters
     * @param apiAccessor
     * @return
     */
    public ExecuteAnswer afterRestart(ExecuteParameters executeParameters, APIAccessor apiAccessor) {
        return returnOkAnswer();
    }
    
  
    /**
     * Bonita call a command, in a transaction. To avoid the transaction, and then access the Bonita API, BonitaCommandDeployment starts a new thread.
     * Then, it will wait the result of the tread, to return a answer.
     * You can decide to not wait the answer, then return immediately the call (with no answer).  
     * @return
     */
    public boolean waitAnswer() {
        return true;
    }

    /**
     * you can implements method like getHelp
     */

    /* ******************************************************************************** */
    /*                                                                                  */
    /** implementation */
    /**
     * this is in the command
     */

    private enum CALL{ EXECUTE, AFTERDEPLOYMENT, AFTERRESTART }
    
    private class RunCommandApi implements Runnable {

        public Long lock = Long.valueOf(0);
        CALL call;
        
        RunCommandApi( CALL call)
        {
            this.call = call;
        }
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
                if (call == CALL.EXECUTE)
                    executeAnswer = bonitaCommandAPI.executeCommandApiAccessor(executeParameters, apiAccessor);
                else if (call== CALL.AFTERDEPLOYMENT)
                    executeAnswer = bonitaCommandAPI.afterDeployment(executeParameters, apiAccessor);
                else if (call== CALL.AFTERRESTART)
                    executeAnswer = bonitaCommandAPI.afterRestart(executeParameters, apiAccessor);
                
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


    private ExecuteAnswer callThread(CALL call,ExecuteParameters executeParameters, TenantServiceAccessor serviceAccessor) {
        RunCommandApi runCommandApi = new RunCommandApi(call);
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
                    Thread.currentThread().interrupt();
                }
            }
        }
        return new ExecuteAnswer();
    }
    /**
     * BonitaCommand call this method. Then, the implementation is to call a Thread to execute this outside the transaction.
     * So, let's create a new thread, and wait for its return
     */
    @Override
    public final ExecuteAnswer executeCommand(ExecuteParameters executeParameters, TenantServiceAccessor serviceAccessor) {
        return callThread(CALL.EXECUTE, executeParameters, serviceAccessor);
    }

    
    @Override
    public ExecuteAnswer afterDeployment(ExecuteParameters executeParameters, TenantServiceAccessor serviceAccessor) 
    {
        return callThread(CALL.AFTERDEPLOYMENT, executeParameters, serviceAccessor);
    }
    
    @Override
    public ExecuteAnswer afterRestart(ExecuteParameters executeParameters, TenantServiceAccessor serviceAccessor) 
    {
        return callThread(CALL.AFTERRESTART, executeParameters, serviceAccessor);

    }
    
    private ExecuteAnswer returnOkAnswer() {
        ExecuteAnswer executeAnswer = new ExecuteAnswer();
        executeAnswer.result.put(CSTANSWER_STATUS, "OK");
        return executeAnswer;
    }
}

package org.bonitasoft.command;

import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import org.bonitasoft.engine.command.SCommandExecutionException;
import org.bonitasoft.engine.command.SCommandParameterizationException;
import org.bonitasoft.engine.command.TenantCommand;
import org.bonitasoft.engine.service.TenantServiceAccessor;
import org.bonitasoft.log.event.BEvent;
import org.bonitasoft.log.event.BEvent.Level;
import org.bonitasoft.log.event.BEventFactory;

/* ******************************************************************************** */
/*                                                                                  */
/* Command Control */
/*                                                                                  */
/* this class is the main control for the command. */
/*
 * your command must implement this command here. The implementation garantie:
 * - to have the same object called (in the Command call, Bonita creates one new object for each
 * call. the getinstance() must decide, you return the same object or a new one
 * - manage the PING and any basic verb
 * - has the same method as the BonitaCommandDeployment.callCommand
 * Different Object:
 * COMMAND => BonitaCommand ==> BonitaCommandAPIAccessor
 * | |
 * -> your command -> Your command
 * afterDeployment(TenantServiceAccessor) afterDeployment(APIAccessor)
 * afterRestart(TenantServiceAccessor) afterRestart(APIAccessor)
 * executeCommand(TenantServiceAccessor) executeCommand(APIAccessor)
 * Depend the API you want to work with, you can derive the BonitaCommand or the BonitaCommandAPIAccessor
 * Ex on a execute:
 * COMMAND.execute
 * BonitaCommand.execute()
 * find the singleton
 * check PING / HELP / afterRestart ==> Extend afterRestart, call afterRestart(APIAccessor)
 * else call executeCommand()
 * BONITACommandAPIAccessor.executeCommand():
 * t = new Thread()
 * t.start()
 * t.run()
 * CreateAPIAccessor
 * executeCommand(APIAccessor)
 */

public abstract class BonitaCommand extends TenantCommand {

    protected static Logger logger = Logger.getLogger(BonitaCommand.class.getName());

    private static String logHeader = "BonitaCommand ~~~";

    public final static String CSTANSWER_STATUS = "status";
    public final static String CSTANSWER_STATUS_V_OK = "OK";

    private final static BEvent eventInternalError = new BEvent(BonitaCommand.class.getName(), 1, Level.ERROR,
            "Internal error", "Internal error, check the log");

    /* ******************************************************************************** */
    /*                                                                                  */
    /* the companion MilkCmdControlAPI call this API */
    /*                                                                                  */
    /* ******************************************************************************** */

    /**
     * this constant is defined too in MilkQuartzJob to have an independent JAR
     */
    public final static String CST_VERB = "verb";
    public final static String CST_VERB_AFTERDEPLOIMENT = "AFTERDEPLOYMENT";
    public final static String CST_VERB_PING = "PING";
    public final static String CST_VERBE_HELP = "HELP";

    /**
     * this constant is defined too in MilkQuartzJob to have an independent JAR
     */
    public final static String CST_TENANTID = "tenantId";
    public final static String CST_PARAMETER_COMMAND = "parametersCmd";

    public final static String CST_RESULT_TIMEINMS = "timeinms";
    public final static String CST_RESULT_LISTEVENTS = "listevents";

    /* ******************************************************************************** */
    /*                                                                                  */
    /* Abstract method */
    /*                                                                                  */
    /* ******************************************************************************** */
    /**
     * Each command instanciate a new Object.
     * if you want that the command use the same object, then return it a static object, else return a
     * "this"
     * The default implementation return the current object
     */

    public BonitaCommand getInstance() {
        return this;
    }

    /**
     * return the name
     */
    public abstract String getName();
    /**
     * 
     *
     */
    public static class ExecuteParameters {

          /**
         * the command may respect the Verb/Parameters protocole. Then, this is the verb
         */
        public String verb;
        public Map<String, Serializable> parametersCommand;

        public ExecuteParameters(String verb) {
              this.verb = verb;
        }

        /** to avoid the cast, return as String parameters. If the parameter is not a String, return null */
        public String getParametersString(String name) {
            return getParametersString(name, null);
        }

        public String getParametersString(String name, String defaultValue) {
            if (parametersCommand.get(name) == null)
                return defaultValue;
            if (parametersCommand.get(name) instanceof String)
                return (String) parametersCommand.get(name);
            return defaultValue;
        }

        /** to avoid the cast, return as Long parameters. If the parameter is not a Long, return null */
        public Long getParametersLong(String name) {
            return getParametersLong(name, null);
        }

        /** to avoid the cast, return as Long parameters. If the parameter is not a Long, return null */
        public Long getParametersLong(String name, Long defaultValue) {
            if (parametersCommand == null)
                return defaultValue;
            if (parametersCommand.get(name) == null)
                return defaultValue;
            if (parametersCommand.get(name) instanceof Long)
                return (Long) parametersCommand.get(name);
            try {
                return Long.parseLong(parametersCommand.get(name).toString());
            } catch (Exception e) {
            }
            return defaultValue;
        }

        /** to avoid the cast, return as Long parameters. If the parameter is not a Long, return null */
        public Integer getParametersInt(String name, Integer defaultValue) {
            if (parametersCommand.get(name) == null)
                return defaultValue;
            if (parametersCommand.get(name) instanceof Integer)
                return (Integer) parametersCommand.get(name);
            try {
                return Integer.parseInt(parametersCommand.get(name).toString());
            } catch (Exception e) {
            }
            return defaultValue;
        }

        /** to avoid the cast, return as String parameters. If the parameter is not a String, return null */
        @SuppressWarnings("unchecked")
        public Map<String, Object> getParametersMap(String name) {
            if (parametersCommand.get(name) == null)
                return null;
            if (parametersCommand.get(name) instanceof Map<?, ?>)
                return (Map<String, Object>) parametersCommand.get(name);
            return null;
        }

        public Boolean getParametersBoolean(String name) {
            return getParametersBoolean(name, null);
        }

        public Boolean getParametersBoolean(String name, Boolean defaultValue) {
            if (parametersCommand.get(name) == null)
                return defaultValue;
            if (parametersCommand.get(name) instanceof Boolean)
                return (Boolean) parametersCommand.get(name);
            return defaultValue;
        }

        /**
         * the original parameters
         */
        public Map<String, Serializable> parameters;

        /**
         * tenant Id
         */
        public long tenantId;

        public void setTenantId(Long tenantId) {
            this.tenantId = tenantId == null ? 1 : tenantId.longValue();
        }
    }

    public static class ExecuteAnswer {

        public boolean logAnswer = true;
        public List<BEvent> listEvents = new ArrayList<>();
        // to keep the serialisation, it must be a HashMap()
        public HashMap<String, Object> result = new HashMap<>();
        /*
         * the command may want to manage directly the serializatble. Then, it can do that, just
         */
        public Serializable resultSerializable = null;
    }

    /**
     * execute the command.
     * 
     * @param verb : if the command respect the Verb / Parameter, then the verb of the command
     * @param parametersCommand : if the command respect the Verb / Parameter, then the parameter of the command
     * @param parameters : the original parameters of the command (so, if the command does not respect the verb/Parameter protocol, the original parameters
     * @param tenantId
     * @param serviceAccessor
     * @return
     */
    public abstract ExecuteAnswer executeCommand(ExecuteParameters executeParameters, TenantServiceAccessor serviceAccessor);

    /**
     * this method is called one time, just after the deployment. So, command is free to finish all initialisation
     * 
     * @param executeParameters
     * @param serviceAccessor
     * @return
     */
    public ExecuteAnswer afterDeployment(ExecuteParameters executeParameters, TenantServiceAccessor serviceAccessor) {

        ExecuteAnswer executeAnswer = new ExecuteAnswer();
        executeAnswer.result.put("status", "OK");
        return executeAnswer;
    }

    /**
     * this method is called one time, just after the deployment. So, command is free to finish all initialisation
     * 
     * @param executeParameters
     * @param serviceAccessor
     * @return
     */
    public ExecuteAnswer afterRestart(ExecuteParameters executeParameters, TenantServiceAccessor serviceAccessor) {
        ExecuteAnswer executeAnswer = new ExecuteAnswer();
        executeAnswer.result.put("status", "OK");
        return executeAnswer;
    }

    @SuppressWarnings("unchecked")
    public ExecuteAnswer executeCommandVerbe( String verb, Map<String, Serializable> parameters, TenantServiceAccessor serviceAccessor) {
        ExecuteParameters executeParameters = new ExecuteParameters(verb);
        executeParameters.parameters = parameters;
        executeParameters.verb = (String) parameters.get(CST_VERB);
        executeParameters.setTenantId((Long) parameters.get(CST_TENANTID));

        executeParameters.parametersCommand = (Map<String, Serializable>) parameters.get(BonitaCommand.CST_PARAMETER_COMMAND);

        return executeCommand(executeParameters, serviceAccessor);
    }

    /**
     * the command may return any help and instruction to the developper
     * 
     * @param parameters
     * @param tenantId
     * @param serviceAccessor
     * @return
     */
    public String getHelp(Map<String, Serializable> parameters, long tenantId, TenantServiceAccessor serviceAccessor) {
        return "No help available";
    }

    /* ******************************************************************************** */
    /*                                                                                  */
    /* the BonitaEngine Command API call this API */
    /*                                                                                  */
    /* ******************************************************************************** */

    /**
     * each call, the command create a new object.
     * The singleton is then use, and decision is take that the method is responsible to save all
     * change
     */
    public Serializable execute(Map<String, Serializable> parameters, TenantServiceAccessor serviceAccessor)
            throws SCommandParameterizationException, SCommandExecutionException {

        BonitaCommand executableCmdControl = getInstance();
        return executableCmdControl.executeSingleton(parameters, serviceAccessor);
    }

    /**
     * Singleton object. All privates members are safe
     * 
     * @param parameters
     * @param serviceAccessor
     * @return
     * @throws SCommandParameterizationException
     * @throws SCommandExecutionException
     */
    @SuppressWarnings("unchecked")
    private Serializable executeSingleton(Map<String, Serializable> parameters, TenantServiceAccessor serviceAccessor)
            throws SCommandParameterizationException, SCommandExecutionException {

        long currentTime = System.currentTimeMillis();
        long startTime = System.currentTimeMillis();
        ExecuteAnswer executeAnswer = null;
        ExecuteParameters executeParameters = null;
        try {

            String verb = (String) parameters.get(CST_VERB);
            executeParameters = new ExecuteParameters( verb);

            executeParameters.parameters = parameters;
            executeParameters.setTenantId((Long) parameters.get(CST_TENANTID));
            executeParameters.parametersCommand = (Map<String, Serializable>) parameters.get(BonitaCommand.CST_PARAMETER_COMMAND);

            logger.fine(logHeader + "BonitaCommand Verb[" + (executeParameters.verb == null ? null : executeParameters.verb.toString()) + "] Tenant[" + executeParameters.tenantId + "]");

            // ------------------- ping ?
            if (CST_VERB_PING.equals(executeParameters.verb)) {
                checkExecuteAfterRestart( parameters, serviceAccessor);

                // logger.info("CmdCreateMilk: ping");
                executeAnswer = new ExecuteAnswer();
                executeAnswer.result.put("ping", "hello world");
                executeAnswer.result.put("status", "OK");
            } else if (CST_VERB_AFTERDEPLOIMENT.equals(executeParameters.verb)) {
                executeAnswer = afterDeployment(executeParameters, serviceAccessor);

                checkExecuteAfterRestart( parameters, serviceAccessor);

            } else if (CST_VERBE_HELP.equals(executeParameters.verb)) {
                checkExecuteAfterRestart( parameters, serviceAccessor);

                executeAnswer = new ExecuteAnswer();
                executeAnswer.result.put("help", getHelp(parameters, executeParameters.tenantId, serviceAccessor));
            } else {
                checkExecuteAfterRestart( parameters, serviceAccessor);

                executeAnswer = executeCommand(executeParameters, serviceAccessor);
            }

        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String exceptionDetails = sw.toString();
            logger.severe("BonitaCommand: ~~~~~~~~~~  : ERROR " + e + " at " + exceptionDetails);
            if (executeAnswer == null)
                executeAnswer = new ExecuteAnswer();

            executeAnswer.listEvents.add(new BEvent(eventInternalError, e.getMessage()));
        } finally {
            if (executeAnswer == null)
                executeAnswer = new ExecuteAnswer();
            executeAnswer.result.put(CST_RESULT_TIMEINMS, System.currentTimeMillis() - currentTime);
            executeAnswer.result.put(CST_RESULT_LISTEVENTS, BEventFactory.getHtml(executeAnswer.listEvents));
            if (executeAnswer.logAnswer)
                logger.info(logHeader + "Verb[" + (executeParameters.verb == null ? "null" : executeParameters.verb.toString()) + "] Tenant["
                        + executeParameters.tenantId + "] Result:" + BEventFactory.getSyntheticErrorLog(executeAnswer.listEvents) + " in "
                        + (System.currentTimeMillis() - startTime) + " ms");

        }

        // ------------------- service
        //ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();
        //ProcessInstanceService processInstanceService = serviceAccessor.getProcessInstanceService();
        //SchedulerService schedulerService = serviceAccessor.getSchedulerService();
        //EventInstanceService eventInstanceService = serviceAccessor.getEventInstanceService();
        if (executeAnswer.resultSerializable != null)
            return executeAnswer.resultSerializable;
        return executeAnswer.result;
    }

    private static Set<String> lockBasedOnCommand = new HashSet<>();

    private void checkExecuteAfterRestart( Map<String, Serializable> parameters, TenantServiceAccessor serviceAccessor) {
        if (getName() == null)
            return;
        // check before the synchronize to limit the synchronize call
        if (lockBasedOnCommand.contains(getName()))
            return;
        synchronized (lockBasedOnCommand) {
            if (lockBasedOnCommand.contains(getName()))
                return;

            lockBasedOnCommand.add(getName());
        }
        // multiple thread can arrive here. Only let one go to the next part*

        ExecuteParameters executeParametersRestart = new ExecuteParameters( null);
        executeParametersRestart.setTenantId((Long) parameters.get(CST_TENANTID));

        afterRestart(executeParametersRestart, serviceAccessor);

    }
}

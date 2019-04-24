package org.bonitasoft.command;

import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 */

public abstract class BonitaCommand extends TenantCommand {

  static Logger logger = Logger.getLogger(BonitaCommand.class.getName());

  static String logHeader = "BonitaCommand ~~~";

  private static BEvent EVENT_INTERNAL_ERROR = new BEvent(BonitaCommand.class.getName(), 1, Level.ERROR,
      "Internal error", "Internal error, check the log");

  /* ******************************************************************************** */
  /*                                                                                  */
  /* the companion MilkCmdControlAPI call this API */
  /*                                                                                  */
  /* ******************************************************************************** */

  /**
   * this constant is defined too in MilkQuartzJob to have an independent JAR
   */
  public static String cstVerb = "verb";
  public static String cstVerbPing = "PING";
  public static String cstVerbHelp = "HELP";

  /**
   * this constant is defined too in MilkQuartzJob to have an independent JAR
   */
  public static String cstTenantId = "tenantId";
  public static String cstParametersCommand = "parametersCmd";

  public static String cstResultTimeInMs = "timeinms";
  public static String cstResultListEvents = "listevents";

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

  public BonitaCommand getInstance()
  {
    return this;
  }
  
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
    /**
     * the original parameters
     */
    public Map<String, Serializable> parameters;
    /**
     * tenant Id
     */
    public long tenantId;
    
   
  }
  
  

  public static class ExecuteAnswer {

    public List<BEvent> listEvents = new ArrayList<BEvent>();
    public HashMap<String, Object> result = new HashMap<String, Object>();
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
    private Serializable executeSingleton(Map<String, Serializable> parameters, TenantServiceAccessor serviceAccessor)
            throws SCommandParameterizationException, SCommandExecutionException {

        long currentTime = System.currentTimeMillis();
        long startTime = System.currentTimeMillis();
        ExecuteParameters executeParameters = new ExecuteParameters();
        ExecuteAnswer executeAnswer = null;

        try {
          
          executeParameters.parameters = parameters;
          executeParameters.verb = (String) parameters.get(cstVerb);
          executeParameters.tenantId = (Long) parameters.get(cstTenantId);
          executeParameters.parametersCommand = (Map<String,Serializable>) parameters.get(BonitaCommand.cstParametersCommand);
            
            logger.info(logHeader + "BonitaCommand Verb[" + (executeParameters.verb==null ? null : executeParameters.verb.toString()) + "] Tenant[" + executeParameters.tenantId + "]");
            
            // ------------------- ping ?
            if (cstVerbPing.equals(executeParameters.verb)) {
                // logger.info("CmdCreateMilk: ping");
                 executeAnswer = new ExecuteAnswer();
              executeAnswer.result.put("ping", "hello world");
              executeAnswer.result.put("status", "OK");
            } else if (cstVerbHelp.equals(executeParameters.verb)) {
              executeAnswer = new ExecuteAnswer();
              executeAnswer.result.put("help", getHelp(parameters, executeParameters.tenantId, serviceAccessor));
            }
            else 
            {
              executeAnswer= executeCommand( executeParameters, serviceAccessor);
            }
           

        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String exceptionDetails = sw.toString();
            logger.severe("BonitaCommand: ~~~~~~~~~~  : ERROR " + e + " at " + exceptionDetails);
            if (executeAnswer==null)
                executeAnswer = new ExecuteAnswer();
            
            executeAnswer.listEvents.add(new BEvent(EVENT_INTERNAL_ERROR, e.getMessage()));
        } finally {
          if (executeAnswer==null)
            executeAnswer = new ExecuteAnswer();
          executeAnswer.result.put(cstResultTimeInMs, System.currentTimeMillis() - currentTime);
          executeAnswer.result.put(cstResultListEvents, BEventFactory.getHtml(executeAnswer.listEvents));
            logger.info(logHeader + "BonitaCommand Verb[" + (executeParameters.verb == null ? "null" : executeParameters.verb.toString()) + "] Tenant["
                    + executeParameters.tenantId + "] Error?" + BEventFactory.isError(executeAnswer.listEvents) + " in "
                    + (System.currentTimeMillis() - startTime) + " ms");

        }

        // ------------------- service
        //ProcessDefinitionService processDefinitionService = serviceAccessor.getProcessDefinitionService();
        //ProcessInstanceService processInstanceService = serviceAccessor.getProcessInstanceService();
        //SchedulerService schedulerService = serviceAccessor.getSchedulerService();
        //EventInstanceService eventInstanceService = serviceAccessor.getEventInstanceService();
        if (executeAnswer.resultSerializable !=null)
          return executeAnswer.resultSerializable;
        return executeAnswer.result;
    }
}

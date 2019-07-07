package org.bonitasoft.command;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.bonitasoft.engine.api.CommandAPI;
import org.bonitasoft.engine.api.PlatformAPI;
import org.bonitasoft.engine.command.CommandCriterion;
import org.bonitasoft.engine.command.CommandDescriptor;
import org.bonitasoft.engine.command.DependencyNotFoundException;
import org.bonitasoft.engine.exception.AlreadyExistsException;
import org.bonitasoft.log.event.BEvent;
import org.bonitasoft.log.event.BEvent.Level;
import org.bonitasoft.log.event.BEventFactory;

public class BonitaCommandDeployment {

  public static String BonitaCommandDeploymentJarName = "bonita-commanddeployment-1.2.jar";

  static Logger logger = Logger.getLogger(BonitaCommandDeployment.class.getName());

  private static String logHeader = "BonitaCommandDeployment";

  private static BEvent EVENT_DEPLOYED_WITH_SUCCESS = new BEvent(BonitaCommandDeployment.class.getName(), 1, Level.INFO,
      "Command deployed with success", "The command are correctly deployed");

  private static BEvent EVENT_ERROR_AT_DEPLOYEMENT = new BEvent(BonitaCommandDeployment.class.getName(), 2,
      Level.APPLICATIONERROR, "Error during deployment of the command", "The command are not deployed",
      "The page can not work", "Check the exception");

  private static BEvent EVENT_NOT_DEPLOYED = new BEvent(BonitaCommandDeployment.class.getName(), 3, Level.ERROR,
      "Command not deployed", "The command is not deployed");

  private static BEvent EVENT_CALL_COMMAND = new BEvent(BonitaCommandDeployment.class.getName(), 4, Level.ERROR,
      "Error during calling a command", "Check the error", "Function can't be executed", "See the error");

  private static BEvent EVENT_PING_ERROR = new BEvent(BonitaCommandDeployment.class.getName(), 5, Level.ERROR,
      "Ping error", "Command does not response", "A command is not responding", "See the error");

  private String commandName;

  /**
   * in order to not have at the same time two deployment for the same command, we have to protect
   * it. So, let's create one object per commandName
   */
  private static Map<String, BonitaCommandDeployment> allDeploymentCommand = new HashMap<String, BonitaCommandDeployment>();

  public static BonitaCommandDeployment getInstance(String commandName) {
    if (allDeploymentCommand.containsKey(commandName))
      return allDeploymentCommand.get(commandName);
    // before create the new object, synchronize it
    synchronized (allDeploymentCommand) {
      if (allDeploymentCommand.containsKey(commandName))
        return allDeploymentCommand.get(commandName);
      logger.info(logHeader + ": instanciate commanddeployment[" + commandName + "]");
      BonitaCommandDeployment commandDeployment = new BonitaCommandDeployment(commandName);
      allDeploymentCommand.put(commandName, commandDeployment);
      return commandDeployment;
    }
  }

  /**
   * Create a command Deployment object
   * 
   * @param commandName
   * @return
   */

  private BonitaCommandDeployment(String commandName) {
    this.commandName = commandName;
  }

  public static class MyCounter {

    public long counter = 0;
  }

  public static MyCounter myCounter = new MyCounter();

  public static class CommandDescription {

    public File pageDirectory;

    public boolean forceDeploy = false;

    /**
     * main class of the command: the command call this class. This class must herit from
     * BonitaCommand
     */
    public String mainCommandClassName;
    /**
     * jar file containging the main class
     */
    public String mainJarFile;

    /**
     * in case of a deployment, we set this description in the command
     */
    public String commandDescription;
    public String[] dependencyJars;

  }

  /* ******************************************************************************** */
  /*                                                                                  */
  /* Check and Deploy the command. */
  /*                                                                                  */
  /* ******************************************************************************** */
  public static class DeployStatus {

    public List<BEvent> listEvents = new ArrayList<BEvent>();;
    public boolean newDeployment = false;
    public boolean alreadyDeployed = true;
    public String message = "";

    /**
     * main signature of the jar file
     */
    public String signatureJar;
    // the command descriptor - may be null in case of issue
    public CommandDescriptor commandDescriptor;
    // if a command exist, the signatudeCommand is returned
    public String signatureCommand;
  }

  /**
   * this method check if the command is already deployed, and do it.
   * this command is call from a CLIENT side
   * @param commandDescription
   * @param logDeepDeployment
   * @param tenantId
   * @param commandAPI
   * @param platFormAPI
   * @return
   */
  public DeployStatus checkAndDeployCommand(CommandDescription commandDescription, boolean logDeepDeployment, long tenantId, CommandAPI commandAPI, PlatformAPI platFormAPI) {
    String message = "";
    Long threadId = null;
    if (logDeepDeployment) {
      synchronized (myCounter) {
        myCounter.counter++;
        threadId = myCounter.counter;
      }
    }
    DeployStatus deployStatus = checkDeployment(commandDescription, threadId, commandAPI);
    logDeploy(threadId, deployStatus.message);

    if (deployStatus.alreadyDeployed)
      return deployStatus;

    message += deployStatus.message + "Deployment required;";

    // at this step, we want to deploy the command. 

    // so no need to have a force deploy here.
    deployStatus = deployCommand(commandDescription, tenantId, threadId, commandAPI, platFormAPI);

    message += deployStatus.message + "Deployed ?[" + deployStatus.newDeployment + "], Success?["
        + BEventFactory.isError(deployStatus.listEvents) + "]";

    // ping the factory
    if (!BEventFactory.isError(deployStatus.listEvents)) {
      Map<String, Object> resultPing = ping(tenantId, commandAPI);
      if (!"OK".equals(resultPing.get("status"))) {
        message += "Ping : [Error]";
        deployStatus.listEvents.add(EVENT_PING_ERROR);
      }

    }
    logDeploy(threadId, message);

    return deployStatus;
  }

  /* ******************************************************************************** */
  /*                                                                                  */
  /* Communication with the command */
  /*                                                                                  */
  /* ******************************************************************************** */

  public Map<String, Object> ping(long tenantId, CommandAPI commandAPI) {
    return callCommand(BonitaCommand.cstVerbPing, null, tenantId, commandAPI);
  }

  /**
   * Call the command, with a verb. ParametersCommand may be null.
   * 
   * @param parameters
   * @param commandAPI
   * @return
   */
  @SuppressWarnings("unchecked")
  public Map<String, Object> callCommand(String verb, HashMap<String, Serializable> parametersCommand, long tenantId, CommandAPI commandAPI) {
    List<BEvent> listEvents = new ArrayList<BEvent>();
    Map<String, Object> resultCommandHashmap = new HashMap<String, Object>();

    final CommandDescriptor command = getCommand(commandAPI);
    if (command == null) {
      logger.info(logHeader + "~~~~~~~~~~ callCommand() No Command[" + commandName + "] deployed");
      listEvents.add(EVENT_NOT_DEPLOYED);
      resultCommandHashmap.put(BonitaCommand.cstResultListEvents, BEventFactory.getHtml(listEvents));
      return resultCommandHashmap;
    }

    try {
      HashMap<String, Serializable> parameters = new HashMap<String, Serializable>();
      parameters.put(BonitaCommand.cstVerb, verb);
      parameters.put(BonitaCommand.cstTenantId, tenantId);
      parameters.put(BonitaCommand.cstParametersCommand, parametersCommand);
      // see the command in CmdMeteor
      logger.info(logHeader + "~~~~~~~~~~ Call Command[" + command.getId() + "] Verb[" + verb + "]");
      final Serializable resultCommand = commandAPI.execute(command.getId(), parameters);

      resultCommandHashmap = (Map<String, Object>) resultCommand;

    } catch (final Exception e) {

      StringWriter sw = new StringWriter();
      e.printStackTrace(new PrintWriter(sw));
      String exceptionDetails = sw.toString();

      logger.severe(logHeader + "~~~~~~~~~~  : ERROR Command[" + command.getId() + "] Verb["
          + verb + "] " + e + " at " + exceptionDetails);
      listEvents.add(new BEvent(EVENT_CALL_COMMAND, e, ""));
    }
    if (listEvents.size() != 0)
      resultCommandHashmap.put(BonitaCommand.cstResultListEvents, BEventFactory.getHtml(listEvents));
    logger.info(logHeader + "~~~~~~~~~~ : END Command[" + command.getId() + "] Verb["
        + verb + "]" + resultCommandHashmap);
    return resultCommandHashmap;
  }

  /* ******************************************************************************** */
  /*                                                                                  */
  /* Internal mechanism to deploy the command */
  /*                                                                                  */
  /*                                                                                  */
  /* ******************************************************************************** */

  /**
   * 
   *
   */
  public static class JarDependencyCommand {

    public String jarName;
    public File pageDirectory;

    public JarDependencyCommand(final String name, File pageDirectory) {
      this.jarName = name;
      this.pageDirectory = pageDirectory;
    }

    public String getCompleteFileName() {
      return pageDirectory.getAbsolutePath() + "/lib/" + jarName;
    }
  }

  /**
   * @param name
   * @param pageDirectory
   * @return
   */
  private static JarDependencyCommand getInstanceJarDependencyCommand(final String name, File pageDirectory) {
    return new JarDependencyCommand(name, pageDirectory);
  }

  /**
   * Deploy the command
   * 
   * @param commandDescription
   * @param tenantId
   * @param threadId to identify the thread uniquely in order to debug it - may be null to remove
   *        the synchronisation part
   * @param pageDirectory
   * @param commandAPI
   * @param platFormAPI
   * @return
   */

  private synchronized DeployStatus deployCommand(CommandDescription commandDescription, long tenantId, Long threadId, CommandAPI commandAPI, PlatformAPI platFormAPI) {

    // this is the synchronized method.
    // First step is to check again if the command was not deployed again by a previous thread
    Long startTime = System.currentTimeMillis();
    DeployStatus deployStatus = null;
    try {
      deployStatus = checkDeployment(commandDescription, threadId, commandAPI);
      if (deployStatus.alreadyDeployed) {
        // it was deployed just now by a previous thread
        deployStatus.message += logDeploy(threadId, "Command Just deployed before;");

        return deployStatus;
      }

      // so deploy / redeploy it
      if (deployStatus.commandDescriptor != null) {
        deployStatus.message += logDeploy(threadId, "Unregister Command[" + deployStatus.commandDescriptor.getId() + "] Signature[" + deployStatus.signatureCommand + "]");

        commandAPI.unregister(deployStatus.commandDescriptor.getId());
        deployStatus.message += logDeploy(threadId, "Unregister Done");
      }

      // deploy now

      List<JarDependencyCommand> jarDependencies = new ArrayList<JarDependencyCommand>();
      jarDependencies.add(getInstanceJarDependencyCommand(commandDescription.mainJarFile, commandDescription.pageDirectory));
      boolean existJarCommandDeployement = false;
      for (String jarName : commandDescription.dependencyJars) {
        if (jarName.equals(BonitaCommandDeploymentJarName))
          existJarCommandDeployement = true;
        jarDependencies.add(getInstanceJarDependencyCommand(jarName, commandDescription.pageDirectory));
      }
      if (!existJarCommandDeployement) {
        JarDependencyCommand bonitaCommandDependency = getInstanceJarDependencyCommand(BonitaCommandDeploymentJarName, commandDescription.pageDirectory);
        if (new File(bonitaCommandDependency.getCompleteFileName()).exists())
          jarDependencies.add(bonitaCommandDependency);
      }

      // pause the engine to deploy a command
      if (platFormAPI != null) {
        platFormAPI.stopNode();
      }

      deployStatus.message += logDeploy(threadId, "DEPLOIMENT Signaturejar[" + deployStatus.signatureJar + "]");

      // -------------------------- first, dependency
      for (final JarDependencyCommand onejar : jarDependencies) {
        long startTimeDependency = System.currentTimeMillis();
        deployStatus.message += logDeploy(threadId, "Manage Dependency[" + onejar.jarName + "]");
        try {
          commandAPI.removeDependency(onejar.jarName);
        } catch (DependencyNotFoundException nf) {
        } catch (Exception e) {
          deployStatus.message += logDeploy(threadId, "ErrorRemoveDependency");
        }
        // load it
        final ByteArrayOutputStream fileContent = new ByteArrayOutputStream();
        final byte[] buffer = new byte[100000];
        int nbRead = 0;
        InputStream inputFileJar = null;
        try {
          inputFileJar = new FileInputStream(onejar.getCompleteFileName());

          while ((nbRead = inputFileJar.read(buffer)) > 0) {
            fileContent.write(buffer, 0, nbRead);
          }

        } catch (final Exception e) {

          deployStatus.message += logDeploy(threadId, "FileErrorLoadDependency: [" + e.getMessage() + "]");
        } finally {
          if (inputFileJar != null)
            inputFileJar.close();
        }
        // message += "Adding jarName [" + onejar.jarName + "] size[" + fileContent.size() + "]...";
        try {
          commandAPI.addDependency(onejar.jarName, fileContent.toByteArray());
          deployStatus.message += logDeploy(threadId, "dependencyDeployed in "+(System.currentTimeMillis()-startTimeDependency)+" ms");
        } catch (AlreadyExistsException ae) {
          deployStatus.message += logDeploy(threadId, "AlreadyExist" + onejar.jarName + "]  in "+(System.currentTimeMillis()-startTimeDependency)+" ms");
        }
      } // end dependency
      // --- register command
      long startTimeCommand = System.currentTimeMillis();
      deployStatus.message += logDeploy(threadId, "Registering Command...");
      deployStatus.commandDescriptor = commandAPI.register(commandName,
          deployStatus.signatureJar + "#" + commandDescription.commandDescription, commandDescription.mainCommandClassName);

      if (platFormAPI != null) {
        platFormAPI.startNode();
      }

      deployStatus.listEvents.add(new BEvent(EVENT_DEPLOYED_WITH_SUCCESS, deployStatus.message));
      deployStatus.newDeployment = true;
      deployStatus.message += logDeploy(threadId, "Command Deployed in " + (System.currentTimeMillis() - startTime) + " ms (registering in "+ (System.currentTimeMillis()-startTimeCommand)+" ms)");

      return deployStatus;

    } catch (Exception e) {
      deployStatus.message += logDeploy(threadId, "ERROR DEPLOIEMENT: CommandNotFoundException[" + e.getMessage() + "]  in " + (System.currentTimeMillis() - startTime) + " ms");
      deployStatus.listEvents.add(new BEvent(EVENT_ERROR_AT_DEPLOYEMENT, e,
          "Command[" + commandName + "SignatureJar[" + deployStatus.signatureJar + "]"));
    }
    return deployStatus;
  }

  /* ******************************************************************************** */
  /*                                                                                  */
  /* Toolbox */
  /*                                                                                  */
  /*                                                                                  */
  /* ******************************************************************************** */

  /**
   * return,if exist, the commandDescriptor. This is based on the name
   * 
   * @param commandAPI
   * @return
   */
  private CommandDescriptor getCommand(CommandAPI commandAPI) {
    final List<CommandDescriptor> listCommands = commandAPI.getAllCommands(0, 1000, CommandCriterion.NAME_ASC);
    for (final CommandDescriptor command : listCommands) {
      if (commandName.equals(command.getName())) {
        return command;
      }
    }
    return null;
  }

  /**
   * check if the command is already deployed
   * 
   * @param commandDescription
   * @param threadId
   * @param commandAPI
   * @return
   */
  private DeployStatus checkDeployment(CommandDescription commandDescription, Long threadId, CommandAPI commandAPI) {
    DeployStatus deployStatus = new DeployStatus();
    File fileJar = new File(commandDescription.pageDirectory.getAbsolutePath() + "/lib/" + commandDescription.mainJarFile);;
    deployStatus.signatureJar = getSignature(fileJar);
    deployStatus.alreadyDeployed = false;
    if (commandDescription.forceDeploy) {
      deployStatus.alreadyDeployed = false;
      return deployStatus;
    }

    deployStatus.commandDescriptor = getCommand(commandAPI);
    if (deployStatus.commandDescriptor == null) {
      deployStatus.alreadyDeployed = false;
      return deployStatus;
    }
    // check the command Signature
    deployStatus.signatureCommand = getSignature(deployStatus.commandDescriptor);
    deployStatus.message += "CommandFile[" + fileJar.getAbsolutePath() + "],SignatureJar[" + deployStatus.signatureJar + "] signatureCommand[" + deployStatus.signatureCommand + "];";
    if (deployStatus.signatureJar.equals(deployStatus.signatureCommand)) {
      deployStatus.alreadyDeployed = true;
      return deployStatus;
    }
    deployStatus.alreadyDeployed = false;
    return deployStatus;
  }

  /**
   * get the signature from the commandDescriptor
   * 
   * @param commandDescriptor
   * @return
   */
  public String getSignature(CommandDescriptor commandDescriptor) {

    if (commandDescriptor.getDescription() != null) {
      int posIndex = commandDescriptor.getDescription().indexOf("#");
      if (posIndex != -1)
        return commandDescriptor.getDescription().substring(0, posIndex);
    }
    return "";
  }

  /**
   * in order to know if the file change on the disk, we need to get a signature.
   * the date of the file is not enough in case of a cluster: the file is read in the database then
   * save on the local disk. On a cluster, on each node, the
   * date
   * will be different then. So, a signature is the reliable information.
   * 
   * @param fileToGetSignature
   * @return
   */
  private String getSignature(File fileToGetSignature) {
    long timeStart = System.currentTimeMillis();
    String checksum = "";
    try {
      //Use MD5 algorithm
      MessageDigest md5Digest = MessageDigest.getInstance("MD5");

      //Get the checksum
      checksum = getFileChecksum(md5Digest, fileToGetSignature);

    } catch (Exception e) {
      checksum = "Date_" + String.valueOf(fileToGetSignature.lastModified());
    } finally {
      logger.info(logHeader + " CheckSum [" + fileToGetSignature.getName() + "] is [" + checksum + "] in "
          + (System.currentTimeMillis() - timeStart) + " ms");
    }
    //see checksum
    return checksum;

  }

  /**
   * calulate the checksum
   * 
   * @param digest
   * @param file
   * @return
   * @throws IOException
   */
  private static String getFileChecksum(MessageDigest digest, File file) throws IOException {
    //Get file input stream for reading the file content
    FileInputStream fis = new FileInputStream(file);

    //Create byte array to read data in chunks
    byte[] byteArray = new byte[1024];
    int bytesCount = 0;

    //Read file data and update in message digest
    while ((bytesCount = fis.read(byteArray)) != -1) {
      digest.update(byteArray, 0, bytesCount);
    } ;

    //close the stream; We don't need it now.
    fis.close();

    //Get the hash's bytes
    byte[] bytes = digest.digest();

    //This bytes[] has bytes in decimal format;
    //Convert it to hexadecimal format
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < bytes.length; i++) {
      sb.append(Integer.toString((bytes[i] & 0xff) + 0x100, 16).substring(1));
    }

    //return complete hash
    return sb.toString();
  }

  /**
   * normalise the log
   * 
   * @param threadId
   * @param message
   */
  private String logDeploy(Long threadId, String message) {

    logger.info(logHeader + " >>>>>>>>>>>>>>>>>>> #" + (threadId == null ? "" : threadId) + " cmd[" + commandName + "] " + message);
    return message + ";";
  }

}

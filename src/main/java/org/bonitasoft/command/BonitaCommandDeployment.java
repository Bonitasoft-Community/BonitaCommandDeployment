package org.bonitasoft.command;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.logging.Logger;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;

import org.bonitasoft.command.BonitaCommandDescription.CommandJarDependency;
import org.bonitasoft.engine.api.CommandAPI;
import org.bonitasoft.engine.api.PlatformAPI;
import org.bonitasoft.engine.command.CommandCriterion;
import org.bonitasoft.engine.command.CommandDescriptor;
import org.bonitasoft.engine.command.DependencyNotFoundException;
import org.bonitasoft.engine.exception.AlreadyExistsException;
import org.bonitasoft.log.event.BEvent;
import org.bonitasoft.log.event.BEvent.Level;
import org.bonitasoft.log.event.BEventFactory;

/**
 * this class manage the Deployment of a command
 * It can check if the command is already deployed, to not deploy it twice and save time. To see if the command is the same,
 * the signature of the JAR file is calculated.
 * If the signature is the same than the JAR upoaded in the command, command is considere as already deployed.
 * For deployment, a list of JAR file is given.
 * A JAR file may have multiple version (bonita-event-1.1, bonita-event-1.2). To avoid to deploy by 2 command two different version, the name of the dependency
 * is given
 * then, the dependency is deployed under its name (bonita-event). THe point is this is not possible to get the version of the dependencies, so its maybe not
 * the
 * last version of the dependencie which may have been deployed
 */
public class BonitaCommandDeployment {

    public static String JAR_NAME = "bonita-commanddeployment-1.3.jar";
    public static String NAME="bonita-commanddeployment";
    public static String VERSION="1.3";

    static Logger logger = Logger.getLogger(BonitaCommandDeployment.class.getName());

    private static String logHeader = "BonitaCommandDeployment";

    private static BEvent EVENT_DEPLOYED_WITH_SUCCESS = new BEvent(BonitaCommandDeployment.class.getName(), 1, Level.INFO,
            "Command deployed with success", "The command are correctly deployed");

    private static BEvent EVENT_ERROR_AT_DEPLOYEMENT = new BEvent(BonitaCommandDeployment.class.getName(), 2,
            Level.APPLICATIONERROR, "Error during deployment of the command", "The command are not deployed",
            "The command can not work", "Check the exception");

    private static BEvent EVENT_NOT_DEPLOYED = new BEvent(BonitaCommandDeployment.class.getName(), 3, Level.ERROR,
            "Command not deployed", "The command is not deployed");

    private static BEvent EVENT_CALL_COMMAND = new BEvent(BonitaCommandDeployment.class.getName(), 4, Level.ERROR,
            "Error during calling a command", "Check the error", "Function can't be executed", "See the error");

    private static BEvent EVENT_PING_ERROR = new BEvent(BonitaCommandDeployment.class.getName(), 5, Level.ERROR,
            "Ping error", "Command does not response", "A command is not responding", "See the error");

    private static BEvent EVENT_MISSING_DEPENDENCY = new BEvent(BonitaCommandDeployment.class.getName(), 6,
            Level.APPLICATIONERROR, "Missing dependency", "A dependency is not found",
            "The command can't work", "Check the exception");

    private static BEvent EVENT_DEPLOY_DEPENDENCY = new BEvent(BonitaCommandDeployment.class.getName(), 7,
            Level.APPLICATIONERROR, "Deploy dependency", "A error arrived when a dependency has to be deployed",
            "The command can't work", "Check the exception");

    /**
     * This is the command Name
     * Note: to deploy a command, more information is needed. This is part of the CommandDescription class
     */
    private String commandName;

    /**
     * in order to not have at the same time two deployment for the same command, we have to protect
     * it. So, let's create one object per commandName
     */
    private static Map<String, BonitaCommandDeployment> allDeploymentCommand = new HashMap<String, BonitaCommandDeployment>();

    /**
     * return an instance. Seach in the local cache, based on the command name.
     * 
     * @param commandDescription
     * @return
     */
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

    private static MyCounter myCounter = new MyCounter();

    public String getName() {
        return commandName;
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
     * 
     * @param commandDescription
     * @param logDeepDeployment
     * @param tenantId
     * @param commandAPI
     * @param platFormAPI
     * @return
     */
    public DeployStatus checkAndDeployCommand(BonitaCommandDescription commandDescription, boolean logDeepDeployment, long tenantId, CommandAPI commandAPI, PlatformAPI platFormAPI) {
        String message = "";
        Long threadId = null;
        if (logDeepDeployment) {
            synchronized (myCounter) {
                myCounter.counter++;
                threadId = myCounter.counter;
            }
        }
        /**
         * force the same name
         */
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
            Map<String, Object> resultPing = afterDeployment(tenantId, commandAPI);
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
    public Map<String, Object> afterDeployment(long tenantId, CommandAPI commandAPI) {
        return callCommand(BonitaCommand.cstVerbAfterDeployment, null, tenantId, commandAPI);
    }

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

    /**
     * Call the command, with a verb. ParametersCommand may be null.
     * 
     * @param parameters
     * @param commandAPI
     * @return
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> callDirectCommand( HashMap<String, Serializable> parametersCommand, long tenantId, CommandAPI commandAPI) {
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
            // see the command in CmdMeteor
            logger.info(logHeader + "~~~~~~~~~~ Call Command[" + command.getId() + "]");
            final Serializable resultCommand = commandAPI.execute(command.getId(), parametersCommand);

            resultCommandHashmap = (Map<String, Object>) resultCommand;

        } catch (final Exception e) {

            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String exceptionDetails = sw.toString();

            logger.severe(logHeader + "~~~~~~~~~~  : ERROR Command[" + command.getId() + "] " + e + " at " + exceptionDetails);
            listEvents.add(new BEvent(EVENT_CALL_COMMAND, e, ""));
        }
        if (listEvents.size() != 0)
            resultCommandHashmap.put(BonitaCommand.cstResultListEvents, BEventFactory.getHtml(listEvents));
        logger.info(logHeader + "~~~~~~~~~~ : END Command[" + command.getId() + "] " + resultCommandHashmap);
        return resultCommandHashmap;
    }
    /* ******************************************************************************** */
    /*                                                                                  */
    /* Internal mechanism to deploy the command */
    /*                                                                                  */
    /*                                                                                  */
    /* ******************************************************************************** */

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

    private synchronized DeployStatus deployCommand(BonitaCommandDescription commandDescription, long tenantId, Long threadId, CommandAPI commandAPI, PlatformAPI platFormAPI) {

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
            // 1. Build the list of all dependencies. There are the basic one, then the one descripbe in the description

            // pause the engine to deploy a command
            if (platFormAPI != null) {
                platFormAPI.stopNode();
            }

            deployStatus.message += logDeploy(threadId, "DEPLOIMENT Signaturejar[" + deployStatus.signatureJar + "]");
            // there are a "lastVersionCheck" in dependencies ? 
            Set<String> lastVersionsCheck = new HashSet<String>();
            for (final CommandJarDependency jarDependency : commandDescription.getListDependenciesToDeploy()) {
                if (jarDependency.lastVersionCheck)
                    lastVersionsCheck.add(jarDependency.name);
            }
            // problem : there are no way to access the current dependency based on the name ! So, no way to detect if the current dependency is newer than the old one.
            Set<String> dependenciesLastVersionCheck = new HashSet<String>();
            if (lastVersionsCheck.size() > 0)
                dependenciesLastVersionCheck = getAllDependencies(lastVersionsCheck);

            // -------------------------- first, dependency
            for (final CommandJarDependency jarDependency : commandDescription.getListDependenciesToDeploy()) {
                boolean deployDependencyOk = true;
                long startTimeDependency = System.currentTimeMillis();
                deployStatus.message += logDeploy(threadId, "Manage Dependency[" + jarDependency.name + "]");
                String nameDependencyToDeploy           = jarDependency.name;

                if (jarDependency.lastVersionCheck) {
                    // check if the version is the last one or not. By default, we have to deploy
                    boolean deployNewDependency = true;
                    for (String existingDependencie : dependenciesLastVersionCheck) {
                        if (existingDependencie.startsWith(jarDependency.name)) {
                            // format is <name>-<version> or just <name>
                            String existingVersion =existingDependencie;
                            if (existingDependencie.lastIndexOf("-")!=-1)
                                existingVersion = existingDependencie.substring(existingDependencie.lastIndexOf("-")+1);
                            boolean isUpper = isUpperVersion(jarDependency.version, existingVersion);
                            deployStatus.message += "Version[" + jarDependency.version + "] <-> existing[" + existingVersion + "] "+(isUpper? "NEW" : "Lower")+";";
                            if (!isUpper)
                                deployNewDependency = false; // we found a better version, no deployment at all
                            if (isUpper) {
                                // we found a old version, delete that one. Do not change the deployNewDependency, we may found a better before.
                                try {
                                    commandAPI.removeDependency( existingDependencie );
                                } catch (DependencyNotFoundException nf) {
                                } catch (Exception e) {
                                    deployStatus.message += logDeploy(threadId, "ErrorRemoveDependency");
                                }

                            }
                        }
                    }

                    if (!deployNewDependency) {
                        deployStatus.message += "Keep Existing;";
                        continue;
                    }
                    deployStatus.message += "DEPLOY;";
                    nameDependencyToDeploy = jarDependency.name+"-"+jarDependency.version;
                }
                else
                {
                    try {
                        commandAPI.removeDependency( jarDependency.name);
                    } catch (DependencyNotFoundException nf) {
                    } catch (Exception e) {
                        deployStatus.message += logDeploy(threadId, "ErrorRemoveDependency");
                    }
                }
                
                // load it
                final ByteArrayOutputStream fileContent = new ByteArrayOutputStream();
                final byte[] buffer = new byte[100000];
                int nbRead = 0;
                InputStream inputFileJar = null;
                try {
                    inputFileJar = new FileInputStream(jarDependency.getCompleteFileName());

                    while ((nbRead = inputFileJar.read(buffer)) > 0) {
                        fileContent.write(buffer, 0, nbRead);
                    }

                } catch (final Exception e) {
                    deployStatus.message += logDeploy(threadId, "FileErrorLoadDependency: [" + e.getMessage() + "]");
                    deployStatus.listEvents.add(new BEvent(EVENT_MISSING_DEPENDENCY, "Dependency[" + jarDependency.name + "] File[" + jarDependency.getCompleteFileName() + "]"));
                    deployDependencyOk = false;
                } finally {
                    if (inputFileJar != null)
                        inputFileJar.close();
                }
                // message += "Adding jarName [" + onejar.jarName + "] size[" + fileContent.size() + "]...";
                if (deployDependencyOk) {
                    try {
                        commandAPI.addDependency( nameDependencyToDeploy, fileContent.toByteArray());
                        deployStatus.message += logDeploy(threadId, "dependencyDeployed in " + (System.currentTimeMillis() - startTimeDependency) + " ms");
                    } catch (AlreadyExistsException ae) {
                        deployStatus.message += logDeploy(threadId, "AlreadyExist" + jarDependency.name + "]  in " + (System.currentTimeMillis() - startTimeDependency) + " ms");
                        deployStatus.listEvents.add(new BEvent(EVENT_DEPLOY_DEPENDENCY, "Dependency[" + jarDependency.name + "] File[" + jarDependency.getCompleteFileName() + "]"));
                    }
                }
            } // end dependency

            // --- register command
            if (!BEventFactory.isError(deployStatus.listEvents)) {
                long startTimeCommand = System.currentTimeMillis();
                deployStatus.message += logDeploy(threadId, "Registering Command...");
                deployStatus.commandDescriptor = commandAPI.register(commandName,
                        deployStatus.signatureJar + "#" + commandDescription.commandDescription, commandDescription.mainCommandClassName);

                deployStatus.listEvents.add(new BEvent(EVENT_DEPLOYED_WITH_SUCCESS, deployStatus.message));
                deployStatus.newDeployment = true;
                deployStatus.message += logDeploy(threadId, "Command Deployed in " + (System.currentTimeMillis() - startTime) + " ms (registering in " + (System.currentTimeMillis() - startTimeCommand) + " ms)");
            }

            if (platFormAPI != null) {
                platFormAPI.startNode();
            }
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
    private DeployStatus checkDeployment(BonitaCommandDescription commandDescription, Long threadId, CommandAPI commandAPI) {
        DeployStatus deployStatus = new DeployStatus();
        deployStatus.alreadyDeployed = true;

        deployStatus.commandDescriptor = getCommand(commandAPI);
        File fileJar = new File(commandDescription.defaultPageDirectory.getAbsolutePath() + "/lib/" + commandDescription.mainJarFile);;
        deployStatus.signatureJar = getSignature(fileJar);

        // forceDeploy ? No doute.
        if (commandDescription.forceDeploy) {
            deployStatus.alreadyDeployed = false;
            return deployStatus;
        }

        // no command ? Deploy.
        if (deployStatus.commandDescriptor == null) {
            deployStatus.alreadyDeployed = false;
            return deployStatus;
        }

        //--- deploy, then check the signature
        if (deployStatus.alreadyDeployed) {

            deployStatus.signatureCommand = getSignature(deployStatus.commandDescriptor);
            deployStatus.message += "CommandFile[" + fileJar.getAbsolutePath() + "],SignatureJar[" + deployStatus.signatureJar + "] signatureCommand[" + deployStatus.signatureCommand + "];";
            deployStatus.alreadyDeployed = deployStatus.signatureJar.equals(deployStatus.signatureCommand);
        }

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

    /**
     * check two versions. return true is newVersion is UPPER than existing version
     * @param newVersion
     * @param existingVersion
     * @return
     */
    private boolean isUpperVersion(String newVersion, String existingVersion) {
        Scanner s1 = null;
        Scanner s2 = null;
        try {
            s1 = new Scanner(newVersion);
            s2 = new Scanner(existingVersion);
            s1.useDelimiter("\\.");
            s2.useDelimiter("\\.");

            while (s1.hasNextInt() && s2.hasNextInt()) {
                int v1 = s1.nextInt();
                int v2 = s2.nextInt();
                if (v1 < v2) {
                    return false;
                } else if (v1 > v2) {
                    return true;
                }
            }
            // s1 = 2.1.x where s2= 2.1 ==> s1 is UPPER
            if (s1.hasNextInt() && s1.nextInt() != 0)
                return true; //str1 has an additional lower-level version number
            if (s2.hasNextInt() && s2.nextInt() != 0)
                return false; //str2 has an additional lower-level version 

            // same version
            return false;
        } // end of try-with-resources
        catch (Exception e) {
            final StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            final String exceptionDetails = sw.toString();
            logger.severe(logHeader + ".isUpperVersion Error during load all Dependencies : " + e.toString()
                    + " : " + exceptionDetails);
        } finally {
            if (s1 != null)
                s1.close();
            if (s2 != null)
                s2.close();
        }
        return true; // by default on error, redeploy
    }

    /**
     * get all depencies which contains a name.
     * Should be nice to have this method in CommandAPI
     * 
     * @param names
     * @return
     */
    private Set<String> getAllDependencies(Set<String> names) {
        Connection con = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        Set<String> listResults = new HashSet<String>();
        try {
            final DataSource dataSource = getDataSourceConnection();
            if (dataSource == null)
                throw new Exception("No datasource available");

            con = dataSource.getConnection();
            if (con == null)
                throw new Exception("No connection available");

            final List<Object> listSqlParameters = new ArrayList<Object>();
            String sqlRequest = "";
            for (String name : names) {
                if (sqlRequest.length() > 0)
                    sqlRequest += " or ";
                sqlRequest += "name like ? ";
                listSqlParameters.add(name + "%");
            }

            sqlRequest = "select * from dependency where " + sqlRequest;
            pstmt = con.prepareStatement(sqlRequest);
            for (int i = 0; i < listSqlParameters.size(); i++) {
                pstmt.setObject(i + 1, listSqlParameters.get(i));
            }

            for (int i = 0; i < listSqlParameters.size(); i++) {
                pstmt.setObject(i + 1, listSqlParameters.get(i));
            }
            rs = pstmt.executeQuery();
            while (rs.next()) {
                listResults.add(rs.getString("name"));
            }
        } catch (final Exception e) {
            final StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            final String exceptionDetails = sw.toString();
            logger.severe(logHeader + ".getAllDependencies Error during load all Dependencies : " + e.toString()
                    + " : " + exceptionDetails);

        } finally {
            if (rs != null) {
                try {
                    rs.close();
                    rs = null;
                } catch (final SQLException localSQLException) {
                }
            }
            if (pstmt != null) {
                try {
                    pstmt.close();
                    pstmt = null;
                } catch (final SQLException localSQLException) {
                }
            }
            if (con != null) {
                try {
                    con.close();
                    con = null;
                } catch (final SQLException localSQLException1) {
                }
            }
        }
        return listResults;
    }

    private String[] listDataSources = new String[] { "java:/comp/env/bonitaSequenceManagerDS", // tomcat
            "java:jboss/datasources/bonitaSequenceManagerDS" }; // jboss 

    private DataSource getDataSourceConnection() {
        // logger.info(loggerLabel+".getDataSourceConnection() start");

        String msg = "";
        List<String> listDatasourceToCheck = new ArrayList<String>();
        for (String dataSourceString : listDataSources)
            listDatasourceToCheck.add(dataSourceString);

        for (String dataSourceString : listDatasourceToCheck) {
            // logger.info(loggerLabel+".getDataSourceConnection() check["+dataSourceString+"]");
            try {
                final Context ctx = new InitialContext();
                final DataSource dataSource = (DataSource) ctx.lookup(dataSourceString);
                // logger.info(loggerLabel+".getDataSourceConnection() ["+dataSourceString+"] isOk");
                return dataSource;
            } catch (NamingException e) {
                // logger.info(loggerLabel+".getDataSourceConnection() error["+dataSourceString+"] : "+e.toString());
                msg += "DataSource[" + dataSourceString + "] : error " + e.toString() + ";";
            }
        }
        logger.severe(logHeader + ".getDataSourceConnection: Can't found a datasource : " + msg);
        return null;
    }
}

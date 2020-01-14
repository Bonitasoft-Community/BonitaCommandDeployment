# BonitaCommandDeployment
Library to deploy automaticaly a command, when needed, and organize the communication between the source and the command.



# Implement your command

A command must implement the method org.bonitasoft.engine.command.TenantCommand.TenantComand.execute().

This method has to concern:

1/ it pass as parameters a TenantServiceAccessor object. This object is not documented, and it's an internal class. Object you can access via this class are not stable, and can change on BonitaVersion. Any command you implement via this object can not work in a next release of Bonita

2/ BonitaEngine open for you a Database Transaction before the call. If you can access a public accessor, all method from the public accessor will not work (like a processAPI.getPendingTask() ): a connection is already opened.

To avoid this concern, BonitaCommandDeployment propose to extend a BonitaCommandApiAccessor class, method executeCommandApiAccessor().

public abstract ExecuteAnswer executeCommandApiAccessor(ExecuteParameters executeParameters, APIAccessor apiAccessor);

This command provide a APIAccessor, where you can get all the public API.

You are welcome to provide the method

public String getHelp(Map<String, Serializable> parameters, long tenantId, TenantServiceAccessor serviceAccessor) 

to return information to the Administrator via the Custom Page Command.

# Deploy a command

Deploying a command via the API is complex.  

To help the deployment, object BonitaCommandDeployment encapsulate all the work. So, a custom page who want to deploy automatically a command can just use this object.

 public DeployStatus checkAndDeployCommand(File pageDirectory, CommandAPI commandAPI, PlatformAPI platFormAPI,
            long tenantId) {

        BonitaCommandDeployment bonitaCommand = BonitaCommandDeployment.getInstance(MilkCmdControl.cstCommandName);

        BonitaCommandDescription commandDescription = new BonitaCommandDescription(bonitaCommand, pageDirectory);
        commandDescription.forceDeploy = false;
        commandDescription.mainCommandClassName = MilkCmdControl.class.getName();
        commandDescription.mainJarFile = "TruckMilk-1.0-Page.jar";
        commandDescription.commandDescription = MilkCmdControl.cstCommandDescription;
        // "bonita-commanddeployment-1.2.jar" is deployed automaticaly with BonitaCommandDeployment
        // commandDescription.dependencyJars = new String[] { "bonita-event-1.5.0.jar", "bonita-properties-2.0.0.jar" }; // "mail-1.5.0-b01.jar", "activation-1.1.jar"};

        commandDescription.addJarDependency("bonita-event", "1.5.0", "bonita-event-1.5.0.jar");
        commandDescription.addJarDependency("bonita-properties", "2.1.0", "bonita-properties-2.1.0.jar");

        DeployStatus deployStatus = bonitaCommand.checkAndDeployCommand(commandDescription, true, tenantId, commandAPI, platFormAPI);
        return deployStatus;
    }

# Dependency policies
Bonita does not manage a list of dependency per command. All dependencies are visible by all command.
Second, it's not possible to get the content of the dependencie, and even not the list of dependencies via the commandAPI.

Is that better to give a dependencie like "bonita-event-1.5.0" ? 
  at one moment, you will have in the dependencies "bonita-event-1.4.0", bonita-event-1.5.0", 
  you don't know which command use which dependencies, you may face a "NoSuchMethod" if the command load bonita-event-1.4.0 first

Is that better to give a dependencie like "bonita-event" and then load the last one?
  Sure, that's help (only one jar file). The point is : 
  	a) to be sure the librairy is ascendent compatible (command developped with 1.4.0 can work with 1.5.0),
  	b) how to load the last version? when you deploy a command embeded 1.4.0, you have to verify first if the JAR currently load is less than 1.4.0 before loading it

BonitaDeployment can manage the two situations:
        commandDescription.addJarDependency("bonita-event-1.5.0", "1.5.0", "bonita-event-1.5.0.jar");
   This method load the JAR file, whatever the existing version
   
        commandDescription.addJarDependencyLastVersion("bonita-event", "1.5.0", "bonita-event-1.5.0.jar");
   This method check the dependencies, and load the version only if the jar in the database is older.
   The second parameters, the version, is then checked according the policy x.y.z 
       
        
  	   
# Internal architecture
 *  BonitaCommand
 	Internal class. This class is the TenantCommand implementation. The engine call
      	public Serializable execute(Map<String, Serializable> parameters, TenantServiceAccessor serviceAccessor)
 	BonitaCommand then call the methode BonitaCommand.executeSingleton()
 	This executeSingleton form the ExcuteParameters object, check if the call is for a PING, a AFTERDEPLOYMENT or anopther one.
 	If this is an another one, the call call the method (abstract)
 		ExecuteAnswer executeCommand(ExecuteParameters executeParameters, TenantServiceAccessor serviceAccessor);
 
 * BonitaCommandApiAccessor
 	A second class, implement BonitaCommand. This class implement the abstract method executeCommand(ExecuteParameters executeParameters, TenantServiceAccessor serviceAccessor);
 	In order to give a (abstract) method 
 	    public abstract ExecuteAnswer executeCommandApiAccessor(ExecuteParameters executeParameters, APIAccessor apiAccessor);
 	this class implement the executeCommand().
 	In the implementation, the method start a new thread. The new thread call the executeCommandApiAccessor(). In the new thread, no Database connextion are open, then a APIAcessor can be created and given.
 	
 	When a user extend BonitaCommandApiAccessor, and implement executeCommandAPIAccessor(), this class extend BonitaCommand and BonitaCommandApiAccessor.
 	
 	Command call BonitaCommand.execute()
 	  This method check the verb, and then call BonitaCommand.executeCommand()
 	BonitaCommand.executeCommand() is implemented by BonitaCommandAPIAccessor()
 	  	BonitaCommandAPIAccessor creates a thread, and the thread call executeCommandApiAccessor()
 	 This is your class.
 
 * BonitaCommandDeployment
   This method simplify the command deployment and call. At deploiment, this class check if the command is already deployed / or is new (compare the Signature on file)
   THis class check and deploy the dependencie, based on the date on the JAR and the date in database (BonitaDependencie does not saved version).
 
 * BonitaCommandDescription
    Class to manipulate the command description.



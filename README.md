# BonitaCommandDeployment
Library to deploy automatically a command, when needed, and organize the communication between the source and the command.

# BonitaCommandDeployment advantages

A command must implement the method org.bonitasoft.engine.command.TenantCommand.TenantComand.execute().

This method has different concerns:

1. it passes as parameters a TenantServiceAccessor object. This object is not documented, and it's an internal class. The object you can access via this class is not stable and can change on BonitaVersion. 
Any command you implement via this object can not work in the next release of Bonita.

2. BonitaEngine open for you a Database Transaction before the call. If you can access a public accessor, all method from the public accessor will not work (like a processAPI.getPendingTask() ): 
a connection is already opened.

3. Deployment is a point: is your software has to redeploy the command every time? It should be better to verify first is the JAR is a new one, and deploy the command only when needed.

4. Dependencies are a clue. All command uses the same dependencies. If a command use BonitaEvent 1.8, and then you deploy a new command with a dependency BonitaEvent 1.6, then the first command will now use Bonita Event 1.6.
So, when you deploy a dependency, it's better to check if the newest version exists, isn't' it?

# Implement your command

Two classes are available:
1. BonitaCommand, if you don't need to access any BonitaAPI
2. BonitaCommandApiAccessor, if you need to access a Bonita API, like ProcessAPI or IdentityAPI

Two classes have a executeCommand

## BonitaCommand.java


Implement the method

```java
public abstract ExecuteAnswer executeCommand(ExecuteParameters executeParameters, TenantServiceAccessor serviceAccessor);
```

Note: the TenantServiceAccessor is a internal class. API changes between each version.
Use this class only if you don't need to access the Bonita API.

Your command should implement this method, and return information. If the command is call with the verb HELP, this method is called to get information on command itself

```java
public String getHelp(Map<String, Serializable> parameters, long tenantId, TenantServiceAccessor serviceAccessor) 
```


## BonitaCommandApiAccessor

Implement the method


```java
public abstract ExecuteAnswer executeCommandApiAccessor(ExecuteParameters executeParameters, APIAccessor apiAccessor);
```
This command provide a APIAccessor, where you can get all the public API.


Your command should implement this method, and return information. If the command is call with the verb HELP, this method is called to get information on command itself

```java
public String getHelp(Map<String, Serializable> parameters, long tenantId, TenantServiceAccessor serviceAccessor) 
```
Note: this command is not in the dedicated thread, you don't have access to the APIAccessor.


## ExecuteParameters() 

Parameters contains:
1. Access to the command parameter (        

```java
public Map<String, Serializable> parametersCommand;
```

Different accessors to help you to cast parameters

```java
public String getParametersString(String name, String defaultValue)
public Long getParametersLong(String name, Long defaultValue) 
...
```

A verb notion: attending command should have a Verb, it return it directly.

 ```java
public String verb;
 ```
 
 If the verb is PING or HELP, class return immediately some informations. Idea is to normalize and extends the Command Usage.
  

## ExecuteAnswer()

Your command return this object.
It contains

1. a List of BonitaEvent. Returning a BonitaEvent gives back more accurate information to the final user. 
Visit Bonita Event librairy.

 ```java
public List<BEvent> listEvents = new ArrayList<BEvent>();
 ```

2. a HashMap of data
       
 ```java
public HashMap<String, Object> result = new HashMap<>();
 ```
 
 3. or directly a serializable. This is useful when the command return a File for example.

 ```java
public Serializable resultSerializable = null;
 ```

# Deploy a command 

Via the Java class BonitaCommandDeployment

Deploying a command via the Bonita API is complex. To simplify the command, use BonitaCommandDeployment.


Just call this method:
```java
BonitaCommandDeployment bonitaCommand = BonitaCommandDeployment.getInstance("MyCommandName");

BonitaCommandDescription commandDescription = new BonitaCommandDescription(bonitaCommand, pageDirectory);

// Fulfill Command Description with Jar Name, Dependencies...


DeployStatus status= bonitaCommand.checkAndDeployCommand(commandDescription, false, tenantId, commandAPI, platFormAPI);
            
```

The method:
1. Check if the command is already deployed
2. If yes, check the signature: if the JAR is the same, then command is not deployed, else that's mean this is a new version
3. Check dependencies, and deploy them if needed. Check the version (based on a String x.y.z) to not deploy a older version.

Complete example:

```java
 

BonitaCommandDeployment bonitaCommand = BonitaCommandDeployment.getInstance(MilkCmdControl.cstCommandName);

// PageDirectory is the place where JAR file can be accessible
BonitaCommandDescription commandDescription = new BonitaCommandDescription(bonitaCommand, pageDirectory);
commandDescription.forceDeploy = false;
commandDescription.mainCommandClassName = MilkCmdControl.class.getName();
commandDescription.mainJarFile = "TruckMilk-1.0-Page.jar";
commandDescription.commandDescription = MilkCmdControl.cstCommandDescription;

commandDescription.addJarDependency("bonita-event", "1.5.0", "bonita-event-1.5.0.jar");
commandDescription.addJarDependency("bonita-properties", "2.1.0", "bonita-properties-2.1.0.jar");

DeployStatus deployStatus = bonitaCommand.checkAndDeployCommand(commandDescription, true, tenantId, commandAPI, platFormAPI);
return deployStatus;

```



# Dependency policies
Bonita does not manage a list of dependency per command. All dependencies are visible by all commands.


It's not possible to get the content of a dependency, and even not the list of dependencies via the commandAPI.

Is that better to give a dependency like "bonita-event-1.5.0" ? 
  at one moment, you will have in the dependencies "bonita-event-1.4.0", bonita-event-1.5.0", 
  you don't know which command use which dependencies, you may face a "NoSuchMethod" if the command load bonita-event-1.4.0 first

Is that better to give a dependency like "bonita-event" and then load the last one?
  Sure, that's help (only one jar file). The point is : 
      a) to be sure the library is ascendent compatible (command developed with 1.4.0 can work with 1.5.0),
      b) how to load the last version? when you deploy a command embedded 1.4.0, you have to verify first if the JAR currently load is less than 1.4.0 before loading it

BonitaDeployment can manage the two situations:
```java
commandDescription.addJarDependency("bonita-event-1.5.0", "1.5.0", "bonita-event-1.5.0.jar");
```
        
   This method load the JAR file, whatever the existing version
   
```java   
commandDescription.addJarDependencyLastVersion("bonita-event", "1.5.0", "bonita-event-1.5.0.jar");
```


This method check the dependencies, and load the version only if the jar in the database is older.
The second parameters, the version, is then checked according the policy x.y.z 
       
        
  	   
# Internal architecture
 
##  BonitaCommand.java
 	Internal class. This class is the TenantCommand implementation. The engine call
 	
```java 	
public Serializable execute(Map<String, Serializable> parameters, TenantServiceAccessor serviceAccessor)
```      	
 	
 	BonitaCommand then call the methode BonitaCommand.executeSingleton()
 	This executeSingleton form the ExcuteParameters object, check if the call is for a PING, a AFTERDEPLOYMENT or anopther one.
 	If this is an another one, the call call the method (abstract)
 	
```java 	
ExecuteAnswer executeCommand(ExecuteParameters executeParameters, TenantServiceAccessor serviceAccessor);
``` 		
 
## BonitaCommandApiAccessor.java

A second class, implement BonitaCommand. This class implement the abstract method
 	 
```java 	
 	executeCommand(ExecuteParameters executeParameters, TenantServiceAccessor serviceAccessor);
``` 
	
In order to give a (abstract) method
 	
```java 	 
 	    public abstract ExecuteAnswer executeCommandApiAccessor(ExecuteParameters executeParameters, APIAccessor apiAccessor);
``` 	    

This class implement the executeCommand().
In the implementation, the method start a new thread. The new thread call the executeCommandApiAccessor(). In the new thread, no Database connextion are open, then a APIAcessor can be created and given.
 	
When a user extend BonitaCommandApiAccessor, and implement executeCommandAPIAccessor(), this class extend BonitaCommand and BonitaCommandApiAccessor.
 	
Command call BonitaCommand.execute()
 	
This method check the verb, and then call BonitaCommand.executeCommand()
 	  
BonitaCommand.executeCommand() is implemented by BonitaCommandAPIAccessor()
 	
BonitaCommandAPIAccessor creates a thread, and the thread call executeCommandApiAccessor()
 	  	

 
## BonitaCommandDeployment.java
  
This method simplify the command deployment and call. At deployment, this class check if the command is already deployed / or is new (compare the Signature on file)
   THis class check and deploy dependencies, based on the date on the JAR and the date in database (BonitaDependencie does not saved version).
 
## BonitaCommandDescription.java

    Class to manipulate the command description.



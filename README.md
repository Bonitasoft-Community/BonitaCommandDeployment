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


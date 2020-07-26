package org.bonitasoft.command;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * To deploy a command, multiple information is required.
 * - jar file and class name
 * - dependencies
 * - pageDirectory where the Jar file
 * Nota: if the pageDirectory is not set, the deployment will use the default one from the context
 */
public class BonitaCommandDescription {

    /**
     * Mandatory. If the jar does not give it's own pageDirectory, this one will be used
     */
    public File defaultPageDirectory = null;

    public boolean forceDeploy = false;


    public BonitaCommandDescription(String commandName, File defaultPageDirectory) {
        this.commandName = commandName;
        this.defaultPageDirectory = defaultPageDirectory;
    }

    public String commandName;
    /**
     * main class of the command: the command call this class. This class must herit from
     * BonitaCommand
     */
    public String mainCommandClassName;
    /**
     * jar file containging the main class
     */
    public String mainJarFile;

    public String mainVersion;

    /**
     * in case of a deployment, we set this description in the command
     */
    public String commandDescription;
    
    /**
     * return the command name
     * @return
     */
    public String getCommandName() {
        return commandName;
    }
    /**
     * give a simple list of JAR name
     */
    public String[] dependencyJars = new String[0];
    
    

    /**
     * Bonita does not build a direct link between the jarName and the command.
     * So, if a command C1 use a file name "bonitaproperties-2.0.0.jar" and an another command C2 use a jar file name "bonitaproperties-1.6.0", we face the
     * folowing issue:
     * - 2 dependencies are loaded, with the 2 jar file
     * - but when the command is executed, one Jar File is used, and we don't know which one : C1 can call "bonitaproperties-1.6.0"
     * - when the command C1 is redeploy, the 2 dependency is still here
     * So, to avoid this behavior, if a JAR file may be used by different command, it's important to keep the last JAR file (assuming they are ascendant
     * compatible).
     * Use this method to describe the dependencie then, specify:
     * - the Dependency name
     * - the file
     * - the version
     * On deployment, command check if a dependency with the same name exist. If yes, it check if the version is upper or not (assuming the version is x.y.z),
     * then redeploy only if the
     * version is upper.
     */
    private List<CommandJarDependency> listDependencyJarsDescription = new ArrayList<CommandJarDependency>();

   
    /**
     * describe a jar description
     * 
     */
    public class CommandJarDependency {
        private String name;
        private String version;
        private String fileName;
        /**
         * lastVersionCheck : the current version of the dependency is study, whith the new one, and depencency is deploy only if the version is detected as new
         */
        private boolean lastVersionCheck;
        /**
         * force the dependency to be deployed, everytime
         */
        private boolean forceDeploy=false;
        /**
         * File Directory. If null, the defaultOne in the commandDescription is used
         */
        private File pageDirectory = null;

        public void setPageDirectory( File pageDirectory ) {
            this.pageDirectory = pageDirectory ;
        }
        public String getCompleteFileName() {
            if (pageDirectory == null)
                return defaultPageDirectory.getAbsolutePath() + "/lib/" + fileName;
            return pageDirectory.getAbsolutePath() + fileName;
        }

        public String toString() {
            return name + "~" + version + "~" + fileName;
        }

        protected CommandJarDependency(String name, String version, String fileName, boolean lastVersionCheck) {
            this.name = name;
            this.fileName = fileName;
            this.version = version;
            this.lastVersionCheck = lastVersionCheck;
        }

        
        protected CommandJarDependency(String jarName) {
            this.name = jarName;
            this.fileName = jarName;
            this.version = "";
        }
        
       
        public String getName() {
            return name;
        }
        
        public void setName(String name) {
            this.name = name;
        }
        
        public String getVersion() {
            return version;
        }
        
        public void setVersion(String version) {
            this.version = version;
        }
        
        public String getFileName() {
            return fileName;
        }
        
        public void setFileName(String fileName) {
            this.fileName = fileName;
        }
        
        public boolean isLastVersionCheck() {
            return lastVersionCheck;
        }
        
        public void setLastVersionCheck(boolean lastVersionCheck) {
            this.lastVersionCheck = lastVersionCheck;
        }
        
        public boolean isForceDeploy() {
            return forceDeploy;
        }
        
        public void setForceDeploy(boolean forceDeploy) {
            this.forceDeploy = forceDeploy;
        }
        
        public File getPageDirectory() {
            return pageDirectory;
        }

    }

    
    public CommandJarDependency addJarDependency(String name, String version, String fileName) {
        CommandJarDependency cmdDependency = new CommandJarDependency(name, version, fileName, false); 
        listDependencyJarsDescription.add( cmdDependency );
        return cmdDependency;
    }

    public CommandJarDependency addJarDependencyLastVersion(String name, String version, String fileName) {
        CommandJarDependency cmdDependency = new CommandJarDependency(name, version, fileName, true);
        listDependencyJarsDescription.add( cmdDependency );
        return cmdDependency;
    }

    public CommandJarDependency addJarDependency(String jarName) {
        CommandJarDependency cmdDependency = new CommandJarDependency(jarName);
        listDependencyJarsDescription.add( cmdDependency );
        return cmdDependency;
    }
    public CommandJarDependency addJarDependency(CommandJarDependency cmdDependency) {
        listDependencyJarsDescription.add( cmdDependency );
        return cmdDependency;
    }

    /**
     * remove an item
     * @param cmdDependency
     */
    public void removeInListDependencies( CommandJarDependency cmdDependency ) {
        listDependencyJarsDescription.remove( cmdDependency );
    }

    /**
     * return the list of dependencie. call can modify it.
     * @return
     */
    public List<CommandJarDependency> getListDependencies()
    {
        return listDependencyJarsDescription;
    }
    /**
     * calculate the list of dependencies used to deploy.
     * The dependency for the command itself is added
     * the dependency for the BonitaCommand is added too.
     * 
     * @return
     */
    public List<CommandJarDependency> getListDependenciesToDeploy() {
        List<CommandJarDependency> listDependencies = new ArrayList<>();
        // add first the command

        listDependencies.add(new CommandJarDependency(commandName, mainVersion, mainJarFile, false));
        boolean existJarCommandDeployement = false;

        // first, add the dependencyJar : only the filename is given
        for (String jarName : dependencyJars) {
            if (jarName.equals(BonitaCommandDeployment.JAR_NAME))
                existJarCommandDeployement = true;
            listDependencies.add(new CommandJarDependency(jarName));
        }
        // Second, add the dependencyJar : only the filename is given
        for (CommandJarDependency jarDependency : listDependencyJarsDescription) {
            if (jarDependency.fileName.startsWith(BonitaCommandDeployment.NAME))
                existJarCommandDeployement = true;
            listDependencies.add(jarDependency);
        }

        if (!existJarCommandDeployement) {
            listDependencies.add( new CommandJarDependency(BonitaCommandDeployment.NAME, BonitaCommandDeployment.VERSION, BonitaCommandDeployment.JAR_NAME, true));
        }
        return listDependencies;
    }
}

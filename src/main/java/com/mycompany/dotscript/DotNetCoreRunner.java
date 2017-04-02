package com.mycompany.dotscript;
import hudson.EnvVars;
import hudson.Launcher;
import hudson.Extension;
import hudson.model.AbstractBuild;
import hudson.util.FormValidation;
import hudson.model.AbstractProject;
import hudson.model.Result;
import hudson.tasks.Builder;
import hudson.tasks.BuildStepDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.util.Scanner;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.io.FileUtils;
import org.jenkinsci.lib.envinject.EnvInjectException;
import org.jenkinsci.plugins.envinject.EnvInjectBuilder;
import hudson.model.BuildListener;
import java.io.Serializable;
import javax.annotation.Nonnull;
import java.util.List;
import java.util.ArrayList;

/**
 * Sample {@link Builder}.
 *
 * <p>
 * When the user configures the project and enables this builder,
 * {@link DescriptorImpl#newInstance(StaplerRequest)} is invoked
 * and a new {@link DotNetCoreRunner} is created. The created
 * instance is persisted to the project configuration XML by using
 * XStream, so this allows you to use instance fields (like {@link #targetCode})
 * to remember the configuration.
 *
 * <p>
 * When a build is performed, the {@link #perform} method will be invoked. 
 *
 * @author Kohsuke Kawaguchi
 */
public class DotNetCoreRunner extends Builder implements Serializable {

    private final String targetCode;

    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public DotNetCoreRunner(String targetCode) {
        this.targetCode = targetCode;
    }
    
    private String getStepIdentificator(String code) throws NoSuchAlgorithmException, UnsupportedEncodingException {
        MessageDigest crypt = MessageDigest.getInstance("SHA-1");
        crypt.reset();
        crypt.update(code.getBytes("UTF-8"));
        return new BigInteger(1, crypt.digest()).toString(16);
    }

    /**
     * We'll use this from the {@code config.jelly}.
     * @return 
     */
    public String getTargetCode() {
        return this.targetCode;
    }

    /**
     *
     * @param build
     * @param launcher
     * @param listener
     * @return
     */
    @Override
    public boolean perform(@Nonnull AbstractBuild<?, ?> build, @Nonnull Launcher launcher, @Nonnull BuildListener listener) throws IOException, InterruptedException {
        File dotScriptWorkspace = new File(new File(build.getWorkspace().toURI()), ".dotscript");
        String folderName = "";
        
        List<String> targetPackages = new ArrayList<String>();
        targetPackages.add("Newtonsoft.Json");
        
        try {
            folderName = this.getStepIdentificator(this.getTargetCode());
        } catch (NoSuchAlgorithmException ex) {
            Logger.getLogger(DotNetCoreRunner.class.getName()).log(Level.SEVERE, null, ex);
            return false;
        }
        
        File uniqueFolder = new File(dotScriptWorkspace, folderName);

        EnvVars env = build.getEnvironment(listener);
        DotNetCommandLine dotNetCommandLine = new DotNetCommandLine(launcher, env, listener, uniqueFolder, "dotscript");
        File marker = new File(dotNetCommandLine.GetProjectFolder(), ".updated");
        boolean recreateProject = false; 
        
        if (!uniqueFolder.exists()) {
            uniqueFolder.mkdir();            
            recreateProject = true;
        } else {
            if (!marker.exists()) {
                uniqueFolder.delete();
                uniqueFolder.mkdir();
                recreateProject = true;
            }            
        }
        
        if (recreateProject) {
            File currentProjectFolder = new File(uniqueFolder, "dotscript");        
            File mainCSharpFile = new File(currentProjectFolder, "TargetCode.cs");                        
            
            dotNetCommandLine.CreateProject();    
            
            this.writeFile(mainCSharpFile, this.getTargetCode());
            this.writeFile(new File(dotNetCommandLine.GetProjectFolder(), "JenkinsExecutor.cs"), this.getFile("dotnet/JenkinsExecutor.cs"));
            this.writeFile(new File(dotNetCommandLine.GetProjectFolder(), "JenkinsManager.cs"), this.getFile("dotnet/JenkinsManager.cs"));
            this.writeFile(new File(dotNetCommandLine.GetProjectFolder(), "Program.cs"), this.getFile("dotnet/Program.cs"));

            for(String thePackage : targetPackages) {
                dotNetCommandLine.AddPackage(thePackage);
            }
            
            dotNetCommandLine.RestoreDependencies();
            dotNetCommandLine.Build();

            marker.createNewFile();
        }        
        
        listener.getLogger().println("##### " + folderName);        
    
        if (!dotNetCommandLine.Run())
        {
            build.setResult(Result.FAILURE);
            return false;
        }
        
        File resultsFile = new File(dotNetCommandLine.GetProjectFolder(), "jenkinsExecution.json");
        
        try {
            this.processResultFile(resultsFile, build, launcher, env, listener);
        } catch (EnvInjectException ex) {
            Logger.getLogger(DotNetCoreRunner.class.getName()).log(Level.SEVERE, null, ex);
            build.setResult(Result.FAILURE);
        }
        
        return true;
    }
    
    private void processResultFile(File resultsFile, AbstractBuild<?,?> build, Launcher launcher, EnvVars env, BuildListener listener) throws IOException, EnvInjectException, InterruptedException { 
        
        String theJson = FileUtils.readFileToString(resultsFile, "utf-8");
        JSONObject jsonObject = JSONObject.fromObject(theJson);
        
        String environmentPath = null;
        String environmentTemplate = "%s=%s\r\n";
        String environmentVariables = "";        
        
        if (jsonObject.containsKey("SavedEnvironment"))
        {
            JSONObject environment = jsonObject.getJSONObject("SavedEnvironment");
               
            Iterator iterator = environment.keys();
            while (iterator.hasNext())
            {               
                String key = iterator.next().toString();
                String value = environment.getString(key);
                
                if (value == null) {
                    value = "null";
                }
                    
                String scapedValue = value.replace("\\", "\\\\");

                environmentVariables += String.format(environmentTemplate, key, scapedValue);                    
            }

            EnvInjectBuilder envInjectBuilder = new EnvInjectBuilder(environmentPath, environmentVariables);
            envInjectBuilder.perform(build, launcher, listener); 
        }
        else
        {
           listener.getLogger().println("##### SavedEnvironment not found");    
        }
    }
    
    private void writeFile(File file, String content) throws FileNotFoundException {
        try (PrintWriter writer = new PrintWriter(file)) {
            writer.write(content);
        }
    }
    
    private String getFile(String fileName) {

	StringBuilder result = new StringBuilder("");

	//Get file from resources folder
	ClassLoader classLoader = getClass().getClassLoader();
	File file = new File(classLoader.getResource(fileName).getFile());

	try (Scanner scanner = new Scanner(file)) {

		while (scanner.hasNextLine()) {
			String line = scanner.nextLine();
			result.append(line).append("\n");
		}

		scanner.close();

	} catch (IOException e) {
		e.printStackTrace();
	}
		
	return result.toString();

    }

    // Overridden for better type safety.
    // If your plugin doesn't really define any property on Descriptor,
    // you don't have to do this.
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    /**
     * Descriptor for {@link DotNetCoreRunner}. Used as a singleton.
     * The class is marked as public so that it can be accessed from views.
     *
     * <p>
     * See {@code src/main/resources/hudson/plugins/hello_world/HelloWorldBuilder/*.jelly}
     * for the actual HTML fragment for the configuration screen.
     */
    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
        
        private static final String pluginName = "DotNet as Script";
        /**
         * To persist global configuration information,
         * simply store it in a field and call save().
         *
         * <p>
         * If you don't want fields to be persisted, use {@code transient}.
         */
        private boolean useFrench;

        /**
         * In order to load the persisted global configuration, you have to 
         * call load() in the constructor.
         */
        public DescriptorImpl() {
            load();
        }

        /**
         * Performs on-the-fly validation of the form field 'name'.
         *
         * @param value
         *      This parameter receives the value that the user has typed.
         * @return
         *      Indicates the outcome of the validation. This is sent to the browser.
         *      <p>
         *      Note that returning {@link FormValidation#error(String)} does not
         *      prevent the form from being saved. It just means that a message
         *      will be displayed to the user. 
         */
        public FormValidation doCheckName(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please set a name");
            if (value.length() < 4)
                return FormValidation.warning("Isn't the name too short?");
            return FormValidation.ok();
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types 
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return DescriptorImpl.pluginName;
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            // To persist global configuration information,
            // set that to properties and call save().
            useFrench = formData.getBoolean("useFrench");
            // ^Can also use req.bindJSON(this, formData);
            //  (easier when there are many fields; need set* methods for this, like setUseFrench)
            save();
            return super.configure(req,formData);
        }

        /**
         * This method returns true if the global configuration says we should speak French.
         *
         * The method name is bit awkward because global.jelly calls this method to determine
         * the initial state of the checkbox by the naming convention.
         */
        public boolean getUseFrench() {
            return useFrench;
        }
    }
}

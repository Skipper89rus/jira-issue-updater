package com.transas.jenkins.plugins.jiraissueupdater;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang.StringUtils;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.FormValidation;
import jenkins.tasks.SimpleBuildStep;

public class JiraIssueUpdaterEx extends Recorder implements SimpleBuildStep {

   private static final Pattern TAG_PATTERN =  Pattern.compile("(?<projName>.+)\\/(?<ver>\\d+\\.\\d+\\.\\d+\\.\\d+)");

   private UpdaterSttings settings;

   private String         tagName;
   private String         fixToken;
   private String         buildLocation;

   public String getTagName() {
      return this.tagName;
   }

   public String getFixToken() {
      return this.fixToken;
   }

   public String getBuildLocation() {
      return this.buildLocation;
   }

   private String getBuildNumFromTag(String buildTag) {
      Matcher m = TAG_PATTERN.matcher(buildTag);
      if ( m.find() && m.groupCount() == 2 && StringUtils.isNotBlank(m.group("ver")) )
         return m.group("ver");
      return "";
   }

   private String getProjNameFromTag(String buildTag) {
      Matcher m = TAG_PATTERN.matcher(buildTag);
      if ( m.find() && m.groupCount() == 2 && StringUtils.isNotBlank(m.group("projName")) )
         return m.group("projName");
      return "";
   }

   private String prepareFixPattern(String fixToken) {
      String fixPattern = "";
      for (char ch : fixToken.toCharArray()) {
         if (Character.isLetter(ch))
            fixPattern += String.format("[%1$s%2$s]",
                  Character.toUpperCase(ch), Character.toLowerCase(ch));
         else
            fixPattern += ch;
      }
      return fixPattern;
   }

   @DataBoundConstructor
   public JiraIssueUpdaterEx(String tagName, String fixToken, String buildLocation) {
      super();

      this.tagName = tagName;
      this.fixToken = fixToken;
      this.buildLocation = buildLocation;

      settings = new UpdaterSttings();
      settings.fixPattern = prepareFixPattern(fixToken);
      settings.buildLocation = buildLocation;
   }

   @Override
   public void perform(Run<?, ?> build, FilePath workspace, Launcher launcher, TaskListener listener)
         throws IOException, InterruptedException {

      EnvVars envVars = build.getEnvironment(listener);
      settings.buildNum = getBuildNumFromTag( envVars.expand(tagName) );
      settings.projectName = getProjNameFromTag( envVars.expand(tagName) );

      if ( settings.buildNum.isEmpty() )
      {
         listener.getLogger().println( "Build number can not be empty! Build tag: " + envVars.expand(tagName) );
         return;
      }

      IssuesUpdater updater = new IssuesUpdater(settings);
      updater.update(build, listener);
   }

   @Override
   public BuildStepMonitor getRequiredMonitorService() {
      return BuildStepMonitor.BUILD;
   }

   @Override
   public DescriptorImpl getDescriptor() {
      return DESCRIPTOR;
   }

   @Extension
   public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

   public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

      private DescriptorImpl() {
         super(JiraIssueUpdaterEx.class);
      }

      @Override
      public String getDisplayName() {
         // Displayed in the publisher section
         return "Extended update relevant JIRA issues";
      }

      @Override
      public String getHelpFile() {
         return "/plugin/jira-issue-updater/help-jira-issue-updater.html";
      }

      @Override
      public boolean isApplicable(Class<? extends AbstractProject> jobType) {
         return true;
      }

      public FormValidation doCheckTagName(@QueryParameter String value) {
         value = StringUtils.strip(value);
         if ( StringUtils.isBlank(value) )
            return FormValidation.error("Build tag name is required.");
         return FormValidation.ok();
      }

      public FormValidation doCheckFixToken(@QueryParameter String value) {
         if ( StringUtils.isBlank(value) )
            return FormValidation.warning("Fix token is empty. All relevant issues will be resolved.");
         return FormValidation.ok();
      }

      public FormValidation doCheckBuildLocation(@QueryParameter String value) {
         value = StringUtils.strip(value);
         if ( StringUtils.isBlank(value) )
            return FormValidation.error("Build location is required.");
         return FormValidation.ok();
      }
   }
}

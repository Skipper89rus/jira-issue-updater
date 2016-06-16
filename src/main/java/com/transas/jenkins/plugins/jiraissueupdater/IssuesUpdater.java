package com.transas.jenkins.plugins.jiraissueupdater;

import hudson.model.Hudson;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.plugins.jira.JiraSite;

import java.io.PrintStream;
import java.util.HashSet;
import java.util.Set;

/**
 * Helps to update the list of issues.
 */
public class IssuesUpdater {
   private UpdaterSttings settings;
   private IssueUpdater   updater;

   public IssuesUpdater(UpdaterSttings settings) {
      this.settings = settings;
   }

   public boolean update(Run<?, ?> build, TaskListener listener) {
      PrintStream logger = listener.getLogger();

      Set<IssueEx> issues = new HashSet<IssueEx>();
      try {
         JiraSite site = JiraSite.get( build.getParent() );
         if (site == null) {
            logger.println("No jira site is configured for this project. This must be a project configuration error");
            build.setResult(Result.FAILURE);
            return true;
         }

         @SuppressWarnings("deprecation")
         String rootUrl = Hudson.getInstance().getRootUrl();
         if (rootUrl == null) {
            logger.println("Jenkins URL is not configured yet. Go to system configuration to set this value");
            build.setResult(Result.FAILURE);
            return true;
         }

         settings.addSttings(rootUrl, site);
         // Collect issues and theirs update data
         IssuesCollector collector = new IssuesCollector();
         collector.findIssues(build, logger, site, issues);
         if ( issues.isEmpty() )
            return true;

         boolean doUpdate = false;
         // In case of workflow, it may be null
         if (site.updateJiraIssueForAllStatus || build.getResult() == null) {
            doUpdate = true;
         } else {
            doUpdate = build.getResult().isBetterOrEqualTo(Result.SUCCESS);
         }

         if (doUpdate) {
            updater = new IssueUpdater(build.getParent(), site, settings, logger);
            simpleUpdate(issues);
         } else {
            // This build didn't work, so carry forward the issues to the next build
            build.addAction( new CarryOverAction(issues) );
         }
      } catch (Exception e) {
         logger.println("Error updating JIRA issues. Saving issues for next build.\n" + e);
         if ( !issues.isEmpty() ) {
            // Updating issues failed, so carry forward issues to the next build
            build.addAction( new CarryOverAction(issues) );
         }
      }
      return true;
   }

   public void simpleUpdate(Set<IssueEx> issues) {

      // copy to prevent ConcurrentModificationException
      Set<IssueEx> copy = new HashSet<IssueEx>(issues);

      for (IssueEx issue : copy) {
         if ( updater.updateIssue(issue) )
            // If no exception is thrown during update, remove from the list as successfully updated
            issues.remove(issue);
      }
   }
}

package com.transas.jenkins.plugin.jiraissueupdater;

import java.io.PrintStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;

import com.atlassian.jira.rest.client.api.domain.Permission;
import com.atlassian.jira.rest.client.api.domain.Transition;
import org.apache.commons.lang.StringUtils;

import com.atlassian.jira.rest.client.api.JiraRestClient;
import com.atlassian.jira.rest.client.api.RestClientException;
import com.atlassian.jira.rest.client.internal.async.AsynchronousJiraRestClientFactory;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;

import hudson.model.Job;
import hudson.plugins.jira.JiraSite;
import hudson.security.ACL;
import org.apache.http.HttpStatus;

public class IssueUpdater
{
   private JiraSite site;
   private UpdaterSttings settings;
   private PrintStream logger;
   private CommentConstructor commentConstructor;
   private JiraRestServiceEx defaultService;
   private Job<?, ?> project;

   public enum UpdateAction
   {
      UA_COMMENT, UA_RESOLVE, UA_LOG_WORK
   }

   public IssueUpdater(Job<?, ?> project, JiraSite site, UpdaterSttings settings, PrintStream logger)
   {
      this.site = site;
      this.settings = settings;
      this.logger = logger;
      this.project = project;

      this.commentConstructor = new CommentConstructor(settings);

      // Будет использован, если не получиться залогиниться от автора коммита
      this.defaultService = createJiraRestService(site.credentials.getUsername());
   }

   /**
    * Creates a remote rest access to this JIRA.
    *
    * @return null if remote access is not supported.
    */
   private JiraRestServiceEx createJiraRestService(String userId)
   {
      if (site == null)
         return null;

      if (StringUtils.isBlank(userId))
         return null;

      final URI uri;
      try
      {
         uri = site.url.toURI();
      }
      catch (URISyntaxException e)
      {
         logger.println(String.format("Failed to convert URI error! Exception: %1$s", e.getLocalizedMessage()));
         return null;
      }

      try
      {
         UsernamePasswordCredentials credentials = CredentialsMatchers.firstOrNull(CredentialsProvider.lookupCredentials(UsernamePasswordCredentials.class, project, ACL.SYSTEM, URIRequirementBuilder.fromUri(site.url.toString()).build()), CredentialsMatchers.withUsername(userId));

         JiraRestClient jiraRestClient = new AsynchronousJiraRestClientFactory().createWithBasicHttpAuthentication(uri, credentials.getUsername(), credentials.getPassword().getPlainText());
         return new JiraRestServiceEx(uri, jiraRestClient, credentials.getUsername(), credentials.getPassword().getPlainText(), JiraSite.DEFAULT_TIMEOUT);
      }
      catch (NullPointerException e)
      {
         logger.println(String.format("Failed to create rest service! Exception: %1$s", e.getLocalizedMessage()));
         return null;
      }
   }

   private String getActionStr(UpdateAction action)
   {
      switch (action)
      {
         case UA_COMMENT:
            return "comment";
         case UA_RESOLVE:
            return "resolve";
         case UA_LOG_WORK:
            return "add log work";
      }
      return "";
   }

   public String getPermissionKey(UpdateAction action)
   {
      switch (action)
      {
         case UA_COMMENT:
            return "ADD_COMMENTS";
         case UA_RESOLVE:
            return "RESOLVE_ISSUES";
         case UA_LOG_WORK:
            return "EDIT_OWN_WORKLOGS";
      }
      return "";
   }

      private JiraRestServiceEx getService(IssueEx issue)
   {
      JiraRestServiceEx service = createJiraRestService(issue.authorId);
      try
      {
         if (!service.getMyPermissions().havePermission(getPermissionKey(issue.action)))
         {
            logger.println(String.format("User '%1$s' hasn't permission to %2$s on issue %3$s", issue.authorId, getActionStr(issue.action), issue.id));
            return null;
         }
      }
      catch (RestClientException e)
      {
         String logMsg = String.format("Failed to update issue %1$s! Exception: %2$s", issue.id, e.getLocalizedMessage());
         if (e.getStatusCode().isPresent())
         {
            switch (e.getStatusCode().get())
            {
               case HttpStatus.SC_NOT_FOUND:
                  logMsg = String.format("Issue '%1$s' not found!", issue.id);
                  break;
               case HttpStatus.SC_FORBIDDEN:
                  logMsg = String.format("User '%1$s' hasn't permission to %2$s on issue %3$s", issue.authorId, getActionStr(issue.action), issue.id);
                  break;
               case HttpStatus.SC_UNAUTHORIZED:
                  logMsg = String.format("Failed to login as '%1$s'! Check '%1$s' password on Credentials page", issue.authorId);
                  break;
            }
         }
         logger.println(logMsg);
         return null;
      }
      catch (NullPointerException e)
      {
         logger.println(String.format("Failed to update issue %1$s! Exception: %2$s", issue.id, e.getLocalizedMessage()));
         return null;
      }
      return service;
   }

   public boolean updateIssue(IssueEx issue)
   {
      logger.println("Updating " + issue.id);
      switch (issue.action)
      {
         case UA_COMMENT:
            return comment(issue);
         case UA_RESOLVE:
            return resolve(issue);
         case UA_LOG_WORK:
            return logWork(issue);
      }
      return false;
   }

   /**
    * Comment given issue.
    */
   public boolean comment(IssueEx issue)
   {
      JiraRestServiceEx service = getService(issue);
      if (service == null)
      {
         logger.println("Issue will be processed from default user!");
         service = defaultService;
      }
      return commentSimple(service, issue);
   }

   /**
    * Resolve given issue.
    */
   public boolean resolve(IssueEx issue)
   {
      JiraRestServiceEx service = getService(issue);
      // Если есть timeSpent, то сначала делаем ворклог
      // Делать ворклог можно только если получилось залогиниться
      boolean logWorkAdded = false;
      if (service != null && issue.timeSpent > 0)
      {
         logger.println(String.format("Work log will be added to resolving issue " + issue.id));
         logWorkAdded = logWorkSimple(service, issue);
      }

      if (service == null)
      {
         logger.println("Issue will be processed from default user!");
         service = defaultService;
      }

      // Если задача уже зарезолвлена и мы не добавили комментарий через ворклог, то комментим
      if (service.isIssueResolved(issue.id) && !logWorkAdded)
      {
         logger.println(String.format("Issue %1$s already resolved. It will be commented.", issue.id));
         return commentSimple(service, issue);
      }
      return resolveSimple(service, issue);
   }

   /**
    * Adds log work for given issue.
    */
   public boolean logWork(IssueEx issue)
   {
      JiraRestServiceEx service = getService(issue);
      if (service == null)
      {
         logger.println("Work log can't be added to " + issue.id + ". Issue will be commented.");
         return commentSimple(defaultService, issue);
      }

      if (issue.timeSpent <= 0)
      {
         logger.println("Work log can't be added to " + issue.id + " because commit time spent is empty. Issue will be commented.");
         return commentSimple(service, issue);
      }

      return logWorkSimple(service, issue);
   }

   public boolean commentSimple(JiraRestServiceEx service, IssueEx issue)
   {
      issue.author = service.getUser(issue.authorId);
      String comment = commentConstructor.createFullComment(issue, true);
      service.addComment(issue.id, comment, settings.groupVisibility, settings.roleVisibility);
      logger.println(String.format("Comment for issue %1$s added: %2$s", issue.id, comment));
      return true;
   }

   public boolean resolveSimple(JiraRestServiceEx service, IssueEx issue)
   {
      // Резолвим на репортера
      String reporter = service.getIssue(issue.id).getReporter().getName();

      issue.author = service.getUser(issue.authorId);
      String comment = commentConstructor.createChangeComment(issue, true);
      boolean res = service.fixIssue(issue, comment, settings.buildNum, reporter);
      if (res)
         logger.println(issue.id + " resolved with comment: " + comment);
      else
         logger.println(issue.id + " can't be resolved.");
      return res;
   }

   public boolean logWorkSimple(JiraRestServiceEx service, IssueEx issue)
   {
      issue.author = service.getUser(issue.authorId);
      String comment = commentConstructor.createChangeComment(issue, false);
      boolean res = service.addWorklog(issue, comment);
      if (res)
         logger.println("Work log added to " + issue.id + " with comment: " + comment);
      else
         logger.println("Work log can't be added to " + issue.id);
      return res;
   }
}
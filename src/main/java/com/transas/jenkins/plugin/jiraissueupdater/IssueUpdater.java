package com.transas.jenkins.plugin.jiraissueupdater;

import java.io.PrintStream;
import java.net.URI;
import java.net.URISyntaxException;

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
import hudson.util.Secret;
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
      this.defaultService = createJiraRestService(site.credentials.getUsername(), site.credentials.getPassword());
   }

   /**
    * Creates a remote rest access to this JIRA.
    *
    * @return null if remote access is not supported.
    */
   private JiraRestServiceEx createJiraRestService(String userName, Secret password)
   {
      if (site == null)
         return null;

      if (StringUtils.isBlank(userName) || StringUtils.isBlank(password.getPlainText()))
         return null;

      final URI uri;
      try
      {
         uri = site.url.toURI();
      }
      catch (URISyntaxException e)
      {
         throw new RuntimeException("Failed to create JiraRestService due to convert URI error");
      }

      JiraRestClient jiraRestClient = new AsynchronousJiraRestClientFactory().createWithBasicHttpAuthentication(uri, userName, password.getPlainText());
      return new JiraRestServiceEx(uri, jiraRestClient, userName, password.getPlainText(), JiraSite.DEFAULT_TIMEOUT);
   }

   /**
    * Creates a remote rest access to this JIRA using user login and password.
    *
    * @return null if remote access is not supported.
    */
   private JiraRestServiceEx createJiraRestService(String userId)
   {
      try
      {
         UsernamePasswordCredentials credentials = CredentialsMatchers.firstOrNull(
               CredentialsProvider.lookupCredentials(UsernamePasswordCredentials.class, project, ACL.SYSTEM,
                     URIRequirementBuilder.fromUri(site.url.toString()).build()),
               CredentialsMatchers.withUsername(userId));

         return createJiraRestService(credentials.getUsername(), credentials.getPassword());
      }
      catch (NullPointerException e)
      {
         return null;
      }
   }

   private JiraRestServiceEx getService(String userId)
   {
      JiraRestServiceEx service = createJiraRestService(userId);
      if (service != null)
         return service;

      logger.println("Login from the commit author failed. Will be used default user.");
      return defaultService;
   }

   public boolean updateIssue(IssueEx issue)
   {
      logger.println("Updating " + issue.id);

      try
      {
         switch (issue.action)
         {
            case UA_COMMENT:
               comment(issue);
               break;
            case UA_RESOLVE:
               resolve(issue);
               break;
            case UA_LOG_WORK:
               logWork(issue);
               break;
         }
      }
      catch (RestClientException e)
      {
         if (e.getStatusCode().isPresent() && e.getStatusCode().get() == HttpStatus.SC_NOT_FOUND)
            logger.println(issue.id + " - JIRA issue not found. Dropping comment from update queue.");

         if (e.getStatusCode().isPresent() && e.getStatusCode().get() == HttpStatus.SC_FORBIDDEN)
         {
            logger.println(issue.id + " - Jenkins JIRA user does not have permissions to comment on this issue. Preserving comment for future update.");
            return false;
         }

         if (e.getStatusCode().isPresent() && e.getStatusCode().get() == HttpStatus.SC_UNAUTHORIZED)
         {
            logger.println(issue.id + " - Jenkins JIRA authentication problem. Preserving comment for future update.");
            return false;
         }

         logger.println(String.format("Commenting failed on %1$s. Carrying over to next build.", issue.id));
         logger.println(e.getLocalizedMessage());
      }

      return true;
   }

   /**
    * Comment given issue.
    */
   public void comment(IssueEx issue)
   {
      JiraRestServiceEx service = getService(issue.authorId);

      issue.author = service.getUser(issue.authorId);
      String comment = commentConstructor.createFullComment(issue, true);
      service.addComment(issue.id, comment, settings.groupVisibility, settings.roleVisibility);
      logger.println(String.format("Comment for issue %1$s added: %2$s", issue.id, comment));
   }

   /**
    * Resolve given issue.
    */
   public void resolve(IssueEx issue)
   {
      if (issue.timeSpent > 0)
      {
         // Если есть timeSpent, то сначала делаем ворклог
         logger.println(String.format("Work log will be added to resolving issue " + issue.id));
         logWork(issue);
      }

      JiraRestServiceEx service = getService(issue.authorId);
      if (service.isIssueResolved(issue.id))
      {
         // Просто комментим
         logger.println(String.format("Issue %1$s already resolved. It will be commented.", issue.id));
         comment(issue);
      }
      else
      {
         // Резолвим на репортера
         String reporter = service.getIssue(issue.id).getReporter().getName();

         issue.author = service.getUser(issue.authorId);
         String comment = commentConstructor.createChangeComment(issue, true);
         if (service.fixIssue(issue, comment, settings.buildNum, reporter))
            logger.println(issue.id + " resolved with comment: " + comment);
         else
            logger.println(issue.id + " can not be resolved.");
      }
   }

   /**
    * Adds log work for given issue.
    */
   public void logWork(IssueEx issue)
   {
      if (StringUtils.isBlank(issue.authorId) || issue.timeSpent <= 0)
      {
         logger.println("Work log can not be added to " + issue.id + " because commit time spent is empty. Issue will be commented.");
         comment(issue);
         return;
      }

      // Если не получается залогиниться от автора коммита, то смысла делать ворклог нет
      JiraRestServiceEx service = createJiraRestService(issue.authorId);
      if (service == null)
      {
         logger.println(String.format("Work log can not be added to %1$s because login from the commit author failed. Issue will be commented.", issue.id));
         comment(issue);
         return;
      }

      issue.author = service.getUser(issue.authorId);
      String comment = commentConstructor.createChangeComment(issue, false);
      if (service.addWorklog(issue, comment))
         logger.println("Work log added to " + issue.id + " with comment: " + comment);
      else
         logger.println("Work log can not be added to " + issue.id);
   }
}
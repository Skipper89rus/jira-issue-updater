package com.transas.jenkins.plugin.jiraissueupdater;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.apache.commons.lang.StringUtils;

import com.atlassian.jira.rest.client.api.JiraRestClient;
import com.atlassian.jira.rest.client.api.domain.Comment;
import com.atlassian.jira.rest.client.api.domain.Transition;
import com.atlassian.jira.rest.client.api.domain.input.ComplexIssueInputFieldValue;
import com.atlassian.jira.rest.client.api.domain.input.FieldInput;
import com.atlassian.jira.rest.client.api.domain.input.TransitionInput;
import com.atlassian.jira.rest.client.api.domain.input.WorklogInputBuilder;

import hudson.plugins.jira.JiraRestService;

public class JiraRestServiceEx extends JiraRestService
{
   private static final Logger LOGGER = Logger.getLogger(JiraRestService.class.getName());

   private final JiraRestClient jiraRestClient;
   private final int timeout;

   public JiraRestServiceEx(URI uri, JiraRestClient jiraRestClient, String username, String password, int timeout)
   {
      super(uri, jiraRestClient, username, password, timeout);

      this.jiraRestClient = jiraRestClient;
      this.timeout = timeout;
   }

   public boolean isIssueResolved(String issueKey)
   {
      try
      {
         int resolvedIssueId = getResolveIssueTransition(issueKey).getId();
         return jiraRestClient.getIssueClient().getIssue(issueKey).get().getStatus().getId() == resolvedIssueId;
      }
      catch (Exception e)
      {
         LOGGER.warning("jira rest client update issue error. cause: " + e.getMessage());
         return true;
      }
   }

   public boolean fixIssue(IssueEx issue, String comment, String buildNumber, String assigneeName)
   {
      try
      {
         ArrayList<FieldInput> fieldInputs = getPreparedFixFields(buildNumber, assigneeName);

         TransitionInput transitionInput = new TransitionInput(getResolveIssueTransition(issue.id).getId(),
               fieldInputs, Comment.valueOf(comment));

         jiraRestClient.getIssueClient().transition(getIssue(issue.id).getTransitionsUri(), transitionInput).get(timeout, TimeUnit.SECONDS);
         return true;
      }
      catch (Exception e)
      {
         LOGGER.warning("jira rest client update issue error. cause: " + e.getMessage());
         return false;
      }
   }

   public boolean addWorklog(IssueEx issue, String comment)
   {
      try
      {
         URI worklogUri = getIssue(issue.id).getWorklogUri();

         WorklogInputBuilder wlBuilder = new WorklogInputBuilder(worklogUri);
         wlBuilder.setMinutesSpent(issue.timeSpent);
         wlBuilder.setComment(comment);

         jiraRestClient.getIssueClient().addWorklog(worklogUri, wlBuilder.build());
         return true;
      }
      catch (Exception e)
      {
         LOGGER.warning("jira rest client update issue error. cause: " + e.getMessage());
         return false;
      }
   }

   private ArrayList<FieldInput> getPreparedFixFields(String buildNumber, String assigneeName)
   {
      ArrayList<FieldInput> fieldInputs = new ArrayList<FieldInput>();
      fieldInputs.add(new FieldInput("resolution", ComplexIssueInputFieldValue.with("name", "Fixed")));
      if (StringUtils.isNotBlank(assigneeName))
      {
         fieldInputs.add(new FieldInput("assignee", ComplexIssueInputFieldValue.with("name", assigneeName)));
      }
      if (StringUtils.isNotBlank(buildNumber))
      {
         fieldInputs.add(new FieldInput("customfield_10012", buildNumber));
      }

      return fieldInputs;
   }

   private Transition getResolveIssueTransition(String issueKey)
   {
      final List<Transition> availableActions = getAvailableActions(issueKey);
      return getTransitionByName(availableActions, "Resolve Issue");
   }

   private Transition getTransitionByName(Iterable<Transition> transitions, String transitionName)
   {
      for (Transition transition : transitions)
      {
         if (transition.getName().equals(transitionName))
         {
            return transition;
         }
      }
      return null;
   }
}

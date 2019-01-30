package com.transas.jenkins.plugin.jiraissueupdater;

import com.atlassian.jira.rest.client.api.domain.User;
import com.transas.jenkins.plugin.jiraissueupdater.IssueUpdater.UpdateAction;

/**
 * One JIRA issue.
 * This class is used to persist crucial issue information
 * so that Jenkins can display it without talking to JIRA.
 */
public final class IssueEx
{
   /**
    * JIRA ID, like "MNG-1235".
    */
   public final String id;

   /**
    * Comment to be used in JIRA for the build
    */
   public String comment;

   /**
    * Action for updater (UA_COMMENT, UA_RESOLVE).
    */
   public UpdateAction action;

   /**
    * Commit timestamp.
    * Will be used as start time while updating issue work log.
    */
   public long timestamp;

   /**
    * Amount of time in minutes logged working on the issue so far or while performing this JIRA operation.
    * Will be added while updating issue in JIRA.
    */
   public int timeSpent;

   /**
    * Author id of the issue update action.
    * Will be used while updating issue.
    */
   public String authorId;

   /**
    * Author of the issue update.
    * Will be used while creating comment for issue.
    */
   public User author;

   public IssueEx(String id)
   {
      this.id = id;
   }

   @Override
   public int hashCode()
   {
      final int prime = 31;
      int result = 1;
      result = prime * result +
            ((id == null) ? 0 : id.hashCode()) +
            ((comment == null) ? 0 : comment.hashCode()) +
            ((action == null) ? 0 : action.hashCode()) +
            ((authorId == null) ? 0 : authorId.hashCode());
      return result;
   }

   private boolean eqField(Object thisField, Object otherField)
   {
      if (thisField == null)
      {
         if (otherField != null)
         {
            return false;
         }
      }
      else if (!thisField.equals(otherField))
      {
         return false;
      }
      return true;
   }

   @Override
   public boolean equals(Object obj)
   {
      if (this == obj)
      {
         return true;
      }
      if (obj == null)
      {
         return false;
      }
      if (getClass() != obj.getClass())
      {
         return false;
      }
      IssueEx other = (IssueEx) obj;
      return eqField(id, other.id) &&
            eqField(comment, other.comment) &&
            eqField(action, other.action) &&
            eqField(authorId, other.authorId);
   }
}
